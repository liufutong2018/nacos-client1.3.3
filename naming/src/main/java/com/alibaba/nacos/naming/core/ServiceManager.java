/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.naming.core;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.naming.consistency.ConsistencyService;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeer;
import com.alibaba.nacos.naming.consistency.persistent.raft.RaftPeerSet;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.Message;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.alibaba.nacos.naming.misc.ServiceStatusSynchronizer;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.Synchronizer;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.push.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * Core manager storing all services in Nacos.
 * Nacos中所有service的核心管理者。其中一个很重要的属性是serviceMap，就是Nacos中的服务注册表。
   该接口中有很多的方法，这些方法可以完成在nacos集群中相关操作的同步。
 * @author nkorange
 */
@Component //监听Service
public class ServiceManager implements RecordListener<Service> {
    
    /**
     * Map(namespace, Map(group::serviceName, Service)).
     */
    // Server端注册表，是一个双层map;
    // 外层map的key为namespaceId，value为内层map
    // 内层map的key为group::serviceName，value为Service
    private final Map<String, Map<String, Service>> serviceMap = new ConcurrentHashMap<>();
    
    private final LinkedBlockingDeque<ServiceKey> toBeUpdatedServicesQueue = new LinkedBlockingDeque<>(1024 * 1024);
    
    // service状态同步器
    private final Synchronizer synchronizer = new ServiceStatusSynchronizer();
    
    private final Lock lock = new ReentrantLock();
    
    // 一致性服务
    @Resource(name = "consistencyDelegate")
    private ConsistencyService consistencyService;
    
    private final SwitchDomain switchDomain;
    
    private final DistroMapper distroMapper;
    
    private final ServerMemberManager memberManager;
    
    private final PushService pushService;
    
    private final RaftPeerSet raftPeerSet;
    
    private int maxFinalizeCount = 3;
    
    private final Object putServiceLock = new Object();
    
    @Value("${nacos.naming.empty-service.auto-clean:false}")
    private boolean emptyServiceAutoClean;
    
    @Value("${nacos.naming.empty-service.clean.initial-delay-ms:60000}")
    private int cleanEmptyServiceDelay;
    
    @Value("${nacos.naming.empty-service.clean.period-time-ms:20000}")
    private int cleanEmptyServicePeriod;
    
    public ServiceManager(SwitchDomain switchDomain, DistroMapper distroMapper, ServerMemberManager memberManager,
            PushService pushService, RaftPeerSet raftPeerSet) {
        this.switchDomain = switchDomain;
        this.distroMapper = distroMapper;
        this.memberManager = memberManager;
        this.pushService = pushService;
        this.raftPeerSet = raftPeerSet;
    }
    
    /** 
     * Init service maneger. Server间的操作
     */
    @PostConstruct
    public void init() {

        // 启动了一个定时任务:每60s当前Server会向其它NacosServer发送一次本机注册表
        // 本机注册表是以各个服务的checksum(字串拼接)形式被发送的
        GlobalExecutor.scheduleServiceReporter(new ServiceReporter(), 60000, TimeUnit.MILLISECONDS);
        
        // 从其它NacosServer获取到注册表中的所有instance的最新状态并更新到本地注册表
        GlobalExecutor.submitServiceUpdateManager(new UpdatedServiceProcessor());
        
        if (emptyServiceAutoClean) { //配置文件可配置此属性
            
            Loggers.SRV_LOG.info("open empty service auto clean job, initialDelay : {} ms, period : {} ms",
                    cleanEmptyServiceDelay, cleanEmptyServicePeriod);
            
            // delay 60s, period 20s;
            
            // This task is not recommended to be performed frequently in order to avoid
            // the possibility that the service cache information may just be deleted
            // and then created due to the heartbeat mechanism
            
            // 启动了一个定时任务：每30s清理一次注册表中的空service
            // 空service，即没有任何instance的service
            GlobalExecutor.scheduleServiceAutoClean(new EmptyServiceAutoClean(), cleanEmptyServiceDelay,
                    cleanEmptyServicePeriod);
        }
        
        try {
            Loggers.SRV_LOG.info("listen for service meta change");
            consistencyService.listen(KeyBuilder.SERVICE_META_KEY_PREFIX, this);
        } catch (NacosException e) {
            Loggers.SRV_LOG.error("listen for service meta change failed!");
        }
    }
    
    public Map<String, Service> chooseServiceMap(String namespaceId) {
        return serviceMap.get(namespaceId);
    }
    
    /**
     * Add a service into queue to update.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param serverIP    target server ip
     * @param checksum    checksum of service
     */
    public void addUpdatedServiceToQueue(String namespaceId, String serviceName, String serverIP, String checksum) {
        lock.lock();
        try {
            toBeUpdatedServicesQueue
                    .offer(new ServiceKey(namespaceId, serviceName, serverIP, checksum), 5, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            toBeUpdatedServicesQueue.poll();
            toBeUpdatedServicesQueue.add(new ServiceKey(namespaceId, serviceName, serverIP, checksum));
            Loggers.SRV_LOG.error("[DOMAIN-STATUS] Failed to add service to be updatd to queue.", e);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean interests(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }
    
    @Override
    public boolean matchUnlistenKey(String key) {
        return KeyBuilder.matchServiceMetaKey(key) && !KeyBuilder.matchSwitchKey(key);
    }
    
    @Override
    public void onChange(String key, Service service) throws Exception {
        try {
            if (service == null) {
                Loggers.SRV_LOG.warn("received empty push from raft, key: {}", key);
                return;
            }
            
            if (StringUtils.isBlank(service.getNamespaceId())) {
                service.setNamespaceId(Constants.DEFAULT_NAMESPACE_ID);
            }
            
            Loggers.RAFT.info("[RAFT-NOTIFIER] datum is changed, key: {}, value: {}", key, service);
            
            Service oldDom = getService(service.getNamespaceId(), service.getName());
            
            if (oldDom != null) {
                oldDom.update(service);
                // re-listen to handle the situation when the underlying listener is removed:
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true),
                                oldDom);
                consistencyService
                        .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false),
                                oldDom);
            } else {
                putServiceAndInit(service);
            }
        } catch (Throwable e) {
            Loggers.SRV_LOG.error("[NACOS-SERVICE] error while processing service update", e);
        }
    }
    
    @Override
    public void onDelete(String key) throws Exception {
        String namespace = KeyBuilder.getNamespace(key);
        String name = KeyBuilder.getServiceName(key);
        Service service = chooseServiceMap(namespace).get(name);
        Loggers.RAFT.info("[RAFT-NOTIFIER] datum is deleted, key: {}", key);
        
        if (service != null) {
            service.destroy();
            consistencyService.remove(KeyBuilder.buildInstanceListKey(namespace, name, true));
            
            consistencyService.remove(KeyBuilder.buildInstanceListKey(namespace, name, false));
            
            consistencyService.unListen(KeyBuilder.buildServiceMetaKey(namespace, name), service);
            Loggers.SRV_LOG.info("[DEAD-SERVICE] {}", service.toJson());
        }
        
        chooseServiceMap(namespace).remove(name);
    }
    
    private class UpdatedServiceProcessor implements Runnable {
        
        //get changed service from other server asynchronously
        @Override
        public void run() { // 从其它NacosServer获取到注册表中的所有instance的最新状态并更新到本地注册表
            ServiceKey serviceKey = null;
            
            try {
                // 运行一个无限循环
                while (true) {
                    try {
                        // 从队列中取出一个元素
                        // toBeUpdatedServicesQueue中存放的是来自于其他Server的服务状态发生变更的服务
                        serviceKey = toBeUpdatedServicesQueue.take();
                    } catch (Exception e) {
                        Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while taking item from LinkedBlockingDeque.");
                    }
                    
                    if (serviceKey == null) {
                        continue;
                    }
                    // 另启动一个线程，来完成ServiceUpdater任务
                    GlobalExecutor.submitServiceUpdate(new ServiceUpdater(serviceKey));
                }
            } catch (Exception e) {
                Loggers.EVT_LOG.error("[UPDATE-DOMAIN] Exception while update service: {}", serviceKey, e);
            }
        }
    }
    
    private class ServiceUpdater implements Runnable {
        
        String namespaceId;
        
        String serviceName;
        
        String serverIP;
        
        public ServiceUpdater(ServiceKey serviceKey) {
            this.namespaceId = serviceKey.getNamespaceId();
            this.serviceName = serviceKey.getServiceName();
            this.serverIP = serviceKey.getServerIP();
        }
        
        @Override
        public void run() {
            try { //更新健康状态
                updatedHealthStatus(namespaceId, serviceName, serverIP);
            } catch (Exception e) {
                Loggers.SRV_LOG
                        .warn("[DOMAIN-UPDATER] Exception while update service: {} from {}, error: {}", serviceName,
                                serverIP, e);
            }
        }
    }
    
    public RaftPeer getMySelfClusterState() {
        return raftPeerSet.local();
    }
    
    /**
     * Update health status of instance in service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param serverIP    source server Ip
     */
    public void updatedHealthStatus(String namespaceId, String serviceName, String serverIP) {
        // 从其它server获取指定服务的数据
        Message msg = synchronizer.get(serverIP, UtilsAndCommons.assembleFullServiceName(namespaceId, serviceName));
        JsonNode serviceJson = JacksonUtils.toObj(msg.getData());
        
        ArrayNode ipList = (ArrayNode) serviceJson.get("ips");
        // 这个map中存放的是来自于其他nacos中的当前服务所包含的所有instance的健康状态
        // map的key文ip:port，value为healthy
        Map<String, String> ipsMap = new HashMap<>(ipList.size());
        // 遍历ipList
        for (int i = 0; i < ipList.size(); i++) {
            // 这个ip字符串的格式是：ip:port_healthy
            String ip = ipList.get(i).asText();
            String[] strings = ip.split("_");
            // 将当前遍历instance的地址及健康状态写入到map
            ipsMap.put(strings[0], strings[1]);
        }
        // 从注册表中获取当前服务
        Service service = getService(namespaceId, serviceName);
        
        if (service == null) {
            return;
        }
        
        boolean changed = false;
        
        // 获取到注册表中当前服务的所有instance
        List<Instance> instances = service.allIPs();
        // 遍历注册表中当前服务的所有instance
        for (Instance instance : instances) {
            // 获取来自于其他nacos的当前遍历instance的健康状态
            boolean valid = Boolean.parseBoolean(ipsMap.get(instance.toIpAddr()));
            // 若当前instance在注册表中记录的状态与外来的状态不一致，则以外来的为准
            if (valid != instance.isHealthy()) {
                changed = true;
                // 将注册表中的instance状态修改为外来的状态
                instance.setHealthy(valid);
                Loggers.EVT_LOG.info("{} {SYNC} IP-{} : {}:{}@{}", serviceName,
                        (instance.isHealthy() ? "ENABLED" : "DISABLED"), instance.getIp(), instance.getPort(),
                        instance.getClusterName());
            }
        }
        
        // 只要有一个instance的状态发生变更，那么这个changed就为true
        if (changed) {
            // 发布状态变更事件
            pushService.serviceChanged(service);
            if (Loggers.EVT_LOG.isDebugEnabled()) {
                StringBuilder stringBuilder = new StringBuilder();
                List<Instance> allIps = service.allIPs();
                for (Instance instance : allIps) {
                    stringBuilder.append(instance.toIpAddr()).append("_").append(instance.isHealthy()).append(",");
                }
                Loggers.EVT_LOG
                        .debug("[HEALTH-STATUS-UPDATED] namespace: {}, service: {}, ips: {}", service.getNamespaceId(),
                                service.getName(), stringBuilder.toString());
            }
        }
        
    }
    
    public Set<String> getAllServiceNames(String namespaceId) {
        return serviceMap.get(namespaceId).keySet();
    }
    
    public Map<String, Set<String>> getAllServiceNames() {
        
        Map<String, Set<String>> namesMap = new HashMap<>(16);
        // 遍历注册表
        for (String namespaceId : serviceMap.keySet()) {
            // serviceMap.get(namespaceId) 是注册表的内层map
            // 其keySet即为所有服务名称(groupid@@微服务名称)
            namesMap.put(namespaceId, serviceMap.get(namespaceId).keySet());
        }
        return namesMap;
    }
    
    public Set<String> getAllNamespaces() {
        return serviceMap.keySet();
    }
    
    public List<String> getAllServiceNameList(String namespaceId) {
        if (chooseServiceMap(namespaceId) == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chooseServiceMap(namespaceId).keySet());
    }
    
    public Map<String, Set<Service>> getResponsibleServices() {
        Map<String, Set<Service>> result = new HashMap<>(16);
        for (String namespaceId : serviceMap.keySet()) {
            result.put(namespaceId, new HashSet<>());
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                Service service = entry.getValue();
                if (distroMapper.responsible(entry.getKey())) {
                    result.get(namespaceId).add(service);
                }
            }
        }
        return result;
    }
    
    public int getResponsibleServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Map.Entry<String, Service> entry : serviceMap.get(namespaceId).entrySet()) {
                if (distroMapper.responsible(entry.getKey())) {
                    serviceCount++;
                }
            }
        }
        return serviceCount;
    }
    
    public int getResponsibleInstanceCount() {
        Map<String, Set<Service>> responsibleServices = getResponsibleServices();
        int count = 0;
        for (String namespaceId : responsibleServices.keySet()) {
            for (Service service : responsibleServices.get(namespaceId)) {
                count += service.allIPs().size();
            }
        }
        
        return count;
    }
    
    /**
     * Fast remove service. 删除服务
     *
     * <p>Remove service bu async.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @throws Exception exception
     */
    public void easyRemoveService(String namespaceId, String serviceName) throws Exception {
        
        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            throw new IllegalArgumentException("specified service not exist, serviceName : " + serviceName);
        }
        // 通过同步服务实现服务的删除；就是会对nacos集合中所有server执行删除操作
        consistencyService.remove(KeyBuilder.buildServiceMetaKey(namespaceId, serviceName));
    }
    
    public void addOrReplaceService(Service service) throws NacosException {
        // 将这个service同步到其他Nacos Server
        consistencyService.put(KeyBuilder.buildServiceMetaKey(service.getNamespaceId(), service.getName()), service);
    }
    
    public void createEmptyService(String namespaceId, String serviceName, boolean local) throws NacosException {
        // local为 true，表示临时实例
        createServiceIfAbsent(namespaceId, serviceName, local, null);
    }
    
    /**
     * Create service if not exist.
     * 如果不存在，请创建服务。
     * @param namespaceId namespace
     * @param serviceName service name
     * @param local       whether create service by local
     * @param cluster     cluster
     * @throws NacosException nacos exception
     */
    public void createServiceIfAbsent(String namespaceId, String serviceName, boolean local, Cluster cluster)
            throws NacosException {
        // 从注册表中获取service
        Service service = getService(namespaceId, serviceName);
        // 若当前注册instance是其提供服务的第一个实例，则注册表中是没有该service的，此时会创建一个service实例
        if (service == null) {
            Loggers.SRV_LOG.info("creating empty service {}:{}", namespaceId, serviceName);
            service = new Service();
            service.setName(serviceName);
            service.setNamespaceId(namespaceId);
            service.setGroupName(NamingUtils.getGroupName(serviceName));
            // now validate the service. if failed, exception will be thrown
            service.setLastModifiedMillis(System.currentTimeMillis());
            // 重新计算校验和
            service.recalculateChecksum();
            if (cluster != null) {
                // cluster和service发生关系
                cluster.setService(service);
                service.getClusterMap().put(cluster.getName(), cluster);
            }
            service.validate();
            // 将service写入到注册表
            putServiceAndInit(service);
            // 对持久实例的操作
            if (!local) {
                addOrReplaceService(service);
            }
        }
    }
    
    /**
     * Register an instance to a service in AP mode. 通过AP模式向服务注册实例。
     * 将instance写入到注册表
     * <p>This method creates service or cluster silently if they don't exist.
     *
     * @param namespaceId id of namespace
     * @param serviceName service name
     * @param instance    instance to register
     * @throws Exception any error occurred in the process
     */
    public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {

        // 创建一个空service，不包含任何instance实例；注意，第三个参数true，表示临时实例
        createEmptyService(namespaceId, serviceName, instance.isEphemeral());

        // 从注册表获取instance
        Service service = getService(namespaceId, serviceName);
        
        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }

        // 将instance写入到service，即写入到了注册表
        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);

    }
    
    /**
     * Update instance to service.
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param instance    instance
     * @throws NacosException nacos exception
     */
    public void updateInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
        
        Service service = getService(namespaceId, serviceName);
        
        if (service == null) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "service not found, namespace: " + namespaceId + ", service: " + serviceName);
        }
        
        if (!service.allIPs().contains(instance)) {
            throw new NacosException(NacosException.INVALID_PARAM, "instance not exist: " + instance);
        }
        
        addInstance(namespaceId, serviceName, instance.isEphemeral(), instance);
    }
    
    /**
     * Add instance to service. 将instance写入到service，即写入到了注册表
     *
     * @param namespaceId namespace
     * @param serviceName service name
     * @param ephemeral   whether instance is ephemeral
     * @param ips         instances
     * @throws NacosException nacos exception
     */
    public void addInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        
        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        // 从注册表中获取service
        Service service = getService(namespaceId, serviceName);
        
        synchronized (service) {
            // 将要注册的instance写入到service，即写入到了注册表
            List<Instance> instanceList = addIpAddresses(service, ephemeral, ips);
            
            Instances instances = new Instances();
            instances.setInstanceList(instanceList);
            // 将本次变更同步给其他nacos
            consistencyService.put(key, instances);
        }
    }
    
    /**
     * Remove instance from service.
     * 处理异步删除、处理注销请求；删除instance
     * @param namespaceId namespace
     * @param serviceName service name
     * @param ephemeral   whether instance is ephemeral
     * @param ips         instances
     * @throws NacosException nacos exception
     */
    public void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Instance... ips)
            throws NacosException {
        // 从注册表获取当前service
        Service service = getService(namespaceId, serviceName);
        
        synchronized (service) {
            // 删除instance
            removeInstance(namespaceId, serviceName, ephemeral, service, ips);
        }
    }

    // 删除instance
    private void removeInstance(String namespaceId, String serviceName, boolean ephemeral, Service service,
            Instance... ips) throws NacosException {
        
        String key = KeyBuilder.buildInstanceListKey(namespaceId, serviceName, ephemeral);
        // 从注册表里删除instance
        List<Instance> instanceList = substractIpAddresses(service, ephemeral, ips);
        
        Instances instances = new Instances();
        instances.setInstanceList(instanceList);
        // 将本次变更同步给其Nacos
        consistencyService.put(key, instances);
    }
    
    // 
    public Instance getInstance(String namespaceId, String serviceName, String cluster, String ip, int port) {
        Service service = getService(namespaceId, serviceName);
        if (service == null) {
            return null;
        }
        
        List<String> clusters = new ArrayList<>();
        clusters.add(cluster);
        
        List<Instance> ips = service.allIPs(clusters);
        if (ips == null || ips.isEmpty()) {
            return null;
        }
        
        for (Instance instance : ips) {
            if (instance.getIp().equals(ip) && instance.getPort() == port) {
                return instance;
            }
        }
        
        return null;
    }
    
    /**
     * Compare and get new instance list. 比较并获得新的实例列表。
     * 修改当前service的instance列表，添加实例 或删除实例
     * @param service   service
     * @param action    {@link UtilsAndCommons#UPDATE_INSTANCE_ACTION_REMOVE} or {@link UtilsAndCommons#UPDATE_INSTANCE_ACTION_ADD}
     * @param ephemeral whether instance is ephemeral
     * @param ips       instances
     * @return instance list after operation
     * @throws NacosException nacos exception
     */
    public List<Instance> updateIpAddresses(Service service, String action, boolean ephemeral, Instance... ips)
            throws NacosException {
        
        // 从其它nacos获取当前服务数据（临时实例数据）
        Datum datum = consistencyService
                .get(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), ephemeral));
        
        // 获取本地注册表中当前服务的所有临时实例
        List<Instance> currentIPs = service.allIPs(ephemeral);
        Map<String, Instance> currentInstances = new HashMap<>(currentIPs.size());
        Set<String> currentInstanceIds = Sets.newHashSet();
        
        // 遍历本地注册表中获取到的实例
        for (Instance instance : currentIPs) {
            // 将其放入到一个map，key为ip:port，value为instance
            currentInstances.put(instance.toIpAddr(), instance);
            // 将当前遍历的instanceId写入到一个set
            currentInstanceIds.add(instance.getInstanceId());
        }
        
        Map<String, Instance> instanceMap;
        if (datum != null) {
            // 将注册表中主机的instance数据替换外来的相同主机的instance数据
            instanceMap = setValid(((Instances) datum.value).getInstanceList(), currentInstances);
        } else {
            instanceMap = new HashMap<>(ips.length);
        }
        
        for (Instance instance : ips) {
            // 若当前service中不包含当前要注册的instance所属cLuster，则创建一个
            if (!service.getClusterMap().containsKey(instance.getClusterName())) {
                Cluster cluster = new Cluster(instance.getClusterName(), service);
                // 初始化cluster的健康检测任务
                cluster.init();
                service.getClusterMap().put(instance.getClusterName(), cluster);
                Loggers.SRV_LOG.warn("cluster: {} not found, ip: {}, will create new cluster with default configuration.",
                                instance.getClusterName(), instance.toJson());
            }
            // 若当前操作为清除操作，则将当前instance从instanceMap中清除
            // 否则就是添加操作，即将当前instance添加到instanceMap中
            if (UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE.equals(action)) {
                instanceMap.remove(instance.getDatumKey());
            } else {
                instance.setInstanceId(instance.generateInstanceId(currentInstanceIds));
                instanceMap.put(instance.getDatumKey(), instance);
            }
            
        }
        
        if (instanceMap.size() <= 0 && UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD.equals(action)) {
            throw new IllegalArgumentException(
                    "ip list can not be empty, service: " + service.getName() + ", ip list: " + JacksonUtils
                            .toJson(instanceMap.values()));
        }
        
        return new ArrayList<>(instanceMap.values());
    }
    
    // 删除Instance
    private List<Instance> substractIpAddresses(Service service, boolean ephemeral, Instance... ips)
            throws NacosException {
        // 修改当前service的instance列表，删除实例
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_REMOVE, ephemeral, ips);
    }
    
    private List<Instance> addIpAddresses(Service service, boolean ephemeral, Instance... ips) throws NacosException {
        // 修改当前service的instance列表，这个修改一共有两种操作: [添加实例] 与 删除实例
        return updateIpAddresses(service, UtilsAndCommons.UPDATE_INSTANCE_ACTION_ADD, ephemeral, ips);
    }
    
    private Map<String, Instance> setValid(List<Instance> oldInstances, Map<String, Instance> map) {
        
        Map<String, Instance> instanceMap = new HashMap<>(oldInstances.size());
        // 遍历外来的instance集合
        for (Instance instance : oldInstances) {
            // 从注册表包含的instance中若可以找到当前遍历的instance, 则将注册表中该主机的instance数据替换外来的数据
            Instance instance1 = map.get(instance.toIpAddr());
            if (instance1 != null) {
                instance.setHealthy(instance1.isHealthy());
                instance.setLastBeat(instance1.getLastBeat());
            }
            instanceMap.put(instance.getDatumKey(), instance);
        }
        return instanceMap;
    }
    
    public Service getService(String namespaceId, String serviceName) {
        if (serviceMap.get(namespaceId) == null) {
            return null;
        }
        return chooseServiceMap(namespaceId).get(serviceName);
    }
    
    public boolean containService(String namespaceId, String serviceName) {
        return getService(namespaceId, serviceName) != null;
    }
    
    /**
     * Put service into manager. 将服务放入注册表serviceMap
     * DCL ，双重检测锁
     * @param service service
     */
    public void putService(Service service) {
        if (!serviceMap.containsKey(service.getNamespaceId())) {
            synchronized (putServiceLock) {
                if (!serviceMap.containsKey(service.getNamespaceId())) {
                    serviceMap.put(service.getNamespaceId(), new ConcurrentHashMap<>(16));
                }
            }
        }
        // 写入到注册表map
        serviceMap.get(service.getNamespaceId()).put(service.getName(), service);
    }

    // 将service写入到注册表
    private void putServiceAndInit(Service service) throws NacosException {
        // 将service写入注册表
        putService(service);
        // 初始化service内部健康检测任务❤
        service.init();
        // 给nacos集群中的当前服务的持久实例、临时实例添加监听
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
        consistencyService
                .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
        Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
    }
    
    /**
     * Search services.
     *
     * @param namespaceId namespace
     * @param regex       search regex
     * @return list of service which searched
     */
    public List<Service> searchServices(String namespaceId, String regex) {
        List<Service> result = new ArrayList<>();
        for (Map.Entry<String, Service> entry : chooseServiceMap(namespaceId).entrySet()) {
            Service service = entry.getValue();
            String key = service.getName() + ":" + ArrayUtils.toString(service.getOwners());
            if (key.matches(regex)) {
                result.add(service);
            }
        }
        
        return result;
    }
    
    public int getServiceCount() {
        int serviceCount = 0;
        for (String namespaceId : serviceMap.keySet()) {
            serviceCount += serviceMap.get(namespaceId).size();
        }
        return serviceCount;
    }
    
    public int getInstanceCount() {
        int total = 0;
        for (String namespaceId : serviceMap.keySet()) {
            for (Service service : serviceMap.get(namespaceId).values()) {
                total += service.allIPs().size();
            }
        }
        return total;
    }
    
    public int getPagedService(String namespaceId, int startPage, int pageSize, String param, String containedInstance,
            List<Service> serviceList, boolean hasIpCount) {
        
        List<Service> matchList;
        
        if (chooseServiceMap(namespaceId) == null) {
            return 0;
        }
        
        if (StringUtils.isNotBlank(param)) {
            StringJoiner regex = new StringJoiner(Constants.SERVICE_INFO_SPLITER);
            for (String s : param.split(Constants.SERVICE_INFO_SPLITER)) {
                regex.add(StringUtils.isBlank(s) ? Constants.ANY_PATTERN
                        : Constants.ANY_PATTERN + s + Constants.ANY_PATTERN);
            }
            matchList = searchServices(namespaceId, regex.toString());
        } else {
            matchList = new ArrayList<>(chooseServiceMap(namespaceId).values());
        }
        
        if (!CollectionUtils.isEmpty(matchList) && hasIpCount) {
            matchList = matchList.stream().filter(s -> !CollectionUtils.isEmpty(s.allIPs()))
                    .collect(Collectors.toList());
        }
        
        if (StringUtils.isNotBlank(containedInstance)) {
            
            boolean contained;
            for (int i = 0; i < matchList.size(); i++) {
                Service service = matchList.get(i);
                contained = false;
                List<Instance> instances = service.allIPs();
                for (Instance instance : instances) {
                    if (containedInstance.contains(":")) {
                        if (StringUtils.equals(instance.getIp() + ":" + instance.getPort(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    } else {
                        if (StringUtils.equals(instance.getIp(), containedInstance)) {
                            contained = true;
                            break;
                        }
                    }
                }
                if (!contained) {
                    matchList.remove(i);
                    i--;
                }
            }
        }
        
        if (pageSize >= matchList.size()) {
            serviceList.addAll(matchList);
            return matchList.size();
        }
        
        for (int i = 0; i < matchList.size(); i++) {
            if (i < startPage * pageSize) {
                continue;
            }
            
            serviceList.add(matchList.get(i));
            
            if (serviceList.size() >= pageSize) {
                break;
            }
        }
        
        return matchList.size();
    }
    
    public static class ServiceChecksum {
        
        public String namespaceId;
        
        // key为服务名称(groupId@@微服务名称); value为该服务对应的checksum
        public Map<String, String> serviceName2Checksum = new HashMap<String, String>();
        
        public ServiceChecksum() {
            this.namespaceId = Constants.DEFAULT_NAMESPACE_ID;
        }
        
        public ServiceChecksum(String namespaceId) {
            this.namespaceId = namespaceId;
        }
        
        /**
         * Add service checksum.
         *
         * @param serviceName service name
         * @param checksum    checksum of service
         */
        public void addItem(String serviceName, String checksum) {
            if (StringUtils.isEmpty(serviceName) || StringUtils.isEmpty(checksum)) {
                Loggers.SRV_LOG.warn("[DOMAIN-CHECKSUM] serviceName or checksum is empty,serviceName: {}, checksum: {}",
                        serviceName, checksum);
                return;
            }
            serviceName2Checksum.put(serviceName, checksum);
        }
    }
    
    private class EmptyServiceAutoClean implements Runnable {
        
        @Override
        public void run() { //启动了一个定时任务：每30s清理一次注册表中的空service
            
            // Parallel flow opening threshold
            // 这是一个并行流开启阈值:当一个namespace中包含的service的数量超过100时，
            // 会将注册创建为一个并行流，否则就是一个串行流
            int parallelSize = 100;
            // 遍历注册表
            // stringServiceMap就是注册表的内层map
            serviceMap.forEach((namespace, stringServiceMap) -> {
                Stream<Map.Entry<String, Service>> stream = null;
                // 若当前遍历的元素(namespace)中包含的服务的数量超出了阈值，则生成一个并行流
                if (stringServiceMap.size() > parallelSize) {
                    stream = stringServiceMap.entrySet().parallelStream(); //并行流
                } else {
                    stream = stringServiceMap.entrySet().stream(); //串行流
                }
                stream.filter(entry -> {
                    final String serviceName = entry.getKey();
                    // 只要当前遍历的服务需要当前server负责，则通过过滤
                    return distroMapper.responsible(serviceName);
                    // 这里的forEach遍历的元素一定是最终需要由当前server处理的服务
                }).forEach(entry -> stringServiceMap.computeIfPresent(entry.getKey(), (serviceName, service) -> {
                    if (service.isEmpty()) {
                        
                        // To avoid violent Service removal, the number of times the Service
                        // experiences Empty is determined by finalizeCnt, and if the specified
                        // value is reached, it is removed

                        // 若当前服务为空的次数超出了最大允许值，则删除这个服务
                        if (service.getFinalizeCount() > maxFinalizeCount) {
                            Loggers.SRV_LOG.warn("namespace : {}, [{}] services are automatically cleaned", namespace,
                                    serviceName);
                            try {
                                // 删除服务
                                easyRemoveService(namespace, serviceName);
                            } catch (Exception e) {
                                Loggers.SRV_LOG.error("namespace : {}, [{}] services are automatically clean has "
                                        + "error : {}", namespace, serviceName, e);
                            }
                        }
                        // 计数器加一 （就对没有超过最大值的起作用）
                        service.setFinalizeCount(service.getFinalizeCount() + 1);
                        
                        Loggers.SRV_LOG
                                .debug("namespace : {}, [{}] The number of times the current service experiences "
                                                + "an empty instance is : {}", namespace, serviceName,
                                        service.getFinalizeCount());
                    } else {
                        // 将计数器归零
                        service.setFinalizeCount(0);
                    }
                    return service;
                }));
            });
        }
    }
    
    private class ServiceReporter implements Runnable {
        
        @Override
        public void run() { // 启动了一个定时任务:每60s当前Server会向其它NacosServer发送一次本机注册表
            try {
                
                // map的key为namespaceId，value为一个Set集合，集合中存放的是当前namespace中所有service的名称
                // 这个map 中存放的是当前注册表中所有服务的名称
                Map<String, Set<String>> allServiceNames = getAllServiceNames();
                
                if (allServiceNames.size() <= 0) {
                    //ignore
                    return;
                }
                // 遍历所有的namespace
                for (String namespaceId : allServiceNames.keySet()) {
                     
                    ServiceChecksum checksum = new ServiceChecksum(namespaceId);
                    
                    // 遍历当前namespace中的所有服务名称
                    for (String serviceName : allServiceNames.get(namespaceId)) {
                        // 若当前服务不归当前Server负责，则直接跳过
                        if (!distroMapper.responsible(serviceName)) {
                            continue;
                        }
                        // 从注册表中获取到当前遍历的服务
                        Service service = getService(namespaceId, serviceName);
                        
                        if (service == null || service.isEmpty()) {
                            continue;
                        }
                        // 重新计算当前service的Checksum
                        service.recalculateChecksum();
                        // 将计算好的checksum写入到map
                        checksum.addItem(serviceName, service.getChecksum());
                    }
                    
                    Message msg = new Message();
                    // 将当前namespace中的所有服务的checksum写入到msg中，将来将msg发送给其他nacos
                    msg.setData(JacksonUtils.toJson(checksum));
                    
                    // 获取到所有nacos
                    Collection<Member> sameSiteServers = memberManager.allMembers();
                    
                    if (sameSiteServers == null || sameSiteServers.size() <= 0) {
                        return;
                    }
                    // 遍历所有nacos，要将msg发送出去
                    for (Member server : sameSiteServers) {
                        // 若当前遍历的server是当前server，则直接跳过
                        if (server.getAddress().equals(NetUtils.localServer())) {
                            continue;
                        }
                        // 将msg发送给当前遍历的server
                        synchronizer.send(server.getAddress(), msg);
                    }
                }
            } catch (Exception e) {
                Loggers.SRV_LOG.error("[DOMAIN-STATUS] Exception while sending service status", e);
            } finally {
                // 开启下一次定时执行
                GlobalExecutor.scheduleServiceReporter(this, switchDomain.getServiceStatusSynchronizationPeriodMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }
    
    private static class ServiceKey {
        
        private String namespaceId;
        
        private String serviceName;
        
        private String serverIP;
        
        private String checksum;
        
        public String getChecksum() {
            return checksum;
        }
        
        public String getServerIP() {
            return serverIP;
        }
        
        public String getServiceName() {
            return serviceName;
        }
        
        public String getNamespaceId() {
            return namespaceId;
        }
        
        public ServiceKey(String namespaceId, String serviceName, String serverIP, String checksum) {
            this.namespaceId = namespaceId;
            this.serviceName = serviceName;
            this.serverIP = serverIP;
            this.checksum = checksum;
        }
        
        @Override
        public String toString() {
            return JacksonUtils.toJson(this);
        }
    }
}

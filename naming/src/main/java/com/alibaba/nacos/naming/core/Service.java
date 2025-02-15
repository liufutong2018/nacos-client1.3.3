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

import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.common.utils.MD5Utils;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.healthcheck.ClientBeatCheckTask;
import com.alibaba.nacos.naming.healthcheck.ClientBeatProcessor;
import com.alibaba.nacos.naming.healthcheck.HealthCheckReactor;
import com.alibaba.nacos.naming.healthcheck.RsInfo;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.Record;
import com.alibaba.nacos.naming.push.PushService;
import com.alibaba.nacos.naming.selector.NoneSelector;
import com.alibaba.nacos.naming.selector.Selector;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Service of Nacos server side
 * 在Nacos客户端的一个微服务名称定义的微服务，在Nacos服务端是以Service实例的形式出现的。
   其类似于ServiceInfo，只不过ServiceInfo是客户端服务，而core/Service是服务端服务。

   com.alibaba.nacos.api.naming.pojo.Service类中有一个属性 protectThreshold，保护阈值。
        与Eureka中的保护闽值对比:
            1)相同点:都是一个0-1的数值，表示健康实例占所有实例的比例
            2)保护方式不同:
                Eureka: 一旦健康实例数量小于阈值，则不再从注册表中清除不健康的实例。
                Nacos: 如果健康实例数量大于阈值，则消费者调用到的都是健康实例。
                       一旦健康实例数量小于阈值，则消费者会从所有实例中进行选择调用，有可能会调用到不健康实例。
                       这样可以保护健康的实例不会被压崩溃
            3)范围不同:
                Eureka: 这个阈值针对的是所有服务中的实例
                Nacos: 这个阈值针对的是当前Service中的服务实例

 * <p>We introduce a 'service --> cluster --> instance' model, in which service stores a list of clusters, which contain 
    SCI模型
 * a list of instances.
 *
 * <p>his class inherits from Service in API module and stores some fields that do not have to expose to client.
 *
 * @author nkorange
 */
@JsonInclude(Include.NON_NULL) //是监听者，也可以是被监听者 
public class Service extends com.alibaba.nacos.api.naming.pojo.Service implements Record, RecordListener<Instances> {
    
    private static final String SERVICE_NAME_SYNTAX = "[0-9a-zA-Z@\\.:_-]+";
    
    @JsonIgnore
    private ClientBeatCheckTask clientBeatCheckTask = new ClientBeatCheckTask(this);
    
    /**
     * Identify the information used to determine how many isEmpty judgments the service has experienced.
     */
    private int finalizeCount = 0;
    
    private String token;
    
    private List<String> owners = new ArrayList<>();
    
    private Boolean resetWeight = false;
    
    private Boolean enabled = true;
    
    private Selector selector = new NoneSelector();
    
    private String namespaceId;
    
    /**
     * IP will be deleted if it has not send beat for some time, default timeout is 30 seconds.
     */
    private long ipDeleteTimeout = 30 * 1000;
    
    private volatile long lastModifiedMillis = 0L;
    
    private volatile String checksum;
    
    /**
     * TODO set customized push expire time.
     */
    private long pushCacheMillis = 0L;
    
    // 重要集台key为clusterName，value为Cluster实例
    private Map<String, Cluster> clusterMap = new HashMap<>();
    
    public Service() {
    }
    
    public Service(String name) {
        super(name);
    }
    
    @JsonIgnore
    public PushService getPushService() {
        return ApplicationUtils.getBean(PushService.class);
    }
    
    public long getIpDeleteTimeout() {
        return ipDeleteTimeout;
    }
    
    public void setIpDeleteTimeout(long ipDeleteTimeout) {
        this.ipDeleteTimeout = ipDeleteTimeout;
    }
    
    /**
     * Process client beat. 处理客户端心跳。
     *
     * @param rsInfo metrics info of server
     */
    public void processClientBeat(final RsInfo rsInfo) {
        
        // 创建一个处理器，其是一个任务
        ClientBeatProcessor clientBeatProcessor = new ClientBeatProcessor();
        clientBeatProcessor.setService(this);
        clientBeatProcessor.setRsInfo(rsInfo);

        // 开启一个立即执行定时任务，执行ClientBeatProcessor任务的run()
        HealthCheckReactor.scheduleNow(clientBeatProcessor);
    }
    
    public Boolean getEnabled() {
        return enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }
    
    public void setLastModifiedMillis(long lastModifiedMillis) {
        this.lastModifiedMillis = lastModifiedMillis;
    }
    
    public Boolean getResetWeight() {
        return resetWeight;
    }
    
    public void setResetWeight(Boolean resetWeight) {
        this.resetWeight = resetWeight;
    }
    
    public Selector getSelector() {
        return selector;
    }
    
    public void setSelector(Selector selector) {
        this.selector = selector;
    }
    
    @Override
    public boolean interests(String key) {
        return KeyBuilder.matchInstanceListKey(key, namespaceId, getName());
    }
    
    @Override
    public boolean matchUnlistenKey(String key) {
        return KeyBuilder.matchInstanceListKey(key, namespaceId, getName());
    }
    
    @Override
    public void onChange(String key, Instances value) throws Exception {
        
        Loggers.SRV_LOG.info("[NACOS-RAFT] datum is changed, key: {}, value: {}", key, value);
        
        for (Instance instance : value.getInstanceList()) {
            
            if (instance == null) {
                // Reject this abnormal instance list:
                throw new RuntimeException("got null instance " + key);
            }
            
            if (instance.getWeight() > 10000.0D) {
                instance.setWeight(10000.0D);
            }
            
            if (instance.getWeight() < 0.01D && instance.getWeight() > 0.0D) {
                instance.setWeight(0.01D);
            }
        }
        
        updateIPs(value.getInstanceList(), KeyBuilder.matchEphemeralInstanceListKey(key));
        
        recalculateChecksum();
    }
    
    @Override
    public void onDelete(String key) throws Exception {
        // ignore
    }
    
    /**
     * Get count of healthy instance in service.
     *
     * @return count of healthy instance
     */
    public int healthyInstanceCount() {
        
        int healthyCount = 0;
        for (Instance instance : allIPs()) {
            if (instance.isHealthy()) {
                healthyCount++;
            }
        }
        return healthyCount;
    }
    
    public boolean triggerFlag() {
        return (healthyInstanceCount() * 1.0 / allIPs().size()) <= getProtectThreshold();
    }
    
    /**
     * Update instances.
     *
     * @param instances instances
     * @param ephemeral whether is ephemeral instance
     */
    public void updateIPs(Collection<Instance> instances, boolean ephemeral) {
        Map<String, List<Instance>> ipMap = new HashMap<>(clusterMap.size());
        for (String clusterName : clusterMap.keySet()) {
            ipMap.put(clusterName, new ArrayList<>());
        }
        
        for (Instance instance : instances) {
            try {
                if (instance == null) {
                    Loggers.SRV_LOG.error("[NACOS-DOM] received malformed ip: null");
                    continue;
                }
                
                if (StringUtils.isEmpty(instance.getClusterName())) {
                    instance.setClusterName(UtilsAndCommons.DEFAULT_CLUSTER_NAME);
                }
                
                if (!clusterMap.containsKey(instance.getClusterName())) {
                    Loggers.SRV_LOG
                            .warn("cluster: {} not found, ip: {}, will create new cluster with default configuration.",
                                    instance.getClusterName(), instance.toJson());
                    Cluster cluster = new Cluster(instance.getClusterName(), this);
                    cluster.init();
                    getClusterMap().put(instance.getClusterName(), cluster);
                }
                
                List<Instance> clusterIPs = ipMap.get(instance.getClusterName());
                if (clusterIPs == null) {
                    clusterIPs = new LinkedList<>();
                    ipMap.put(instance.getClusterName(), clusterIPs);
                }
                
                clusterIPs.add(instance);
            } catch (Exception e) {
                Loggers.SRV_LOG.error("[NACOS-DOM] failed to process ip: " + instance, e);
            }
        }
        
        for (Map.Entry<String, List<Instance>> entry : ipMap.entrySet()) {
            //make every ip mine
            List<Instance> entryIPs = entry.getValue();
            clusterMap.get(entry.getKey()).updateIps(entryIPs, ephemeral);
        }
        
        setLastModifiedMillis(System.currentTimeMillis());
        getPushService().serviceChanged(this);
        StringBuilder stringBuilder = new StringBuilder();
        
        for (Instance instance : allIPs()) {
            stringBuilder.append(instance.toIpAddr()).append("_").append(instance.isHealthy()).append(",");
        }
        
        Loggers.EVT_LOG.info("[IP-UPDATED] namespace: {}, service: {}, ips: {}", getNamespaceId(), getName(),
                stringBuilder.toString());
        
    }
    
    /**
     * Init service.  初始化Service内部健康检测任务
     */
    public void init() {

        // 开启定时清除过期instance任务（清除临时实例）
        HealthCheckReactor.scheduleCheck(clientBeatCheckTask);

        // 开启了当前service所包含的所有cluster的健康检测任务
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            entry.getValue().setService(this);
            // 开启当前遍历cluster的健康检测任务：
            //  将当前cluster包含的所有instance的心跳检测任务定时添加到一个任务队列taskQueue
            //  即将当前cluster所包含的[持久实例]的心跳任务添加到taskQueue
            entry.getValue().init();
        }
    }
    
    /**
     * Destroy service.
     *
     * @throws Exception exception
     */
    public void destroy() throws Exception {
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            entry.getValue().destroy();
        }
        HealthCheckReactor.cancelCheck(clientBeatCheckTask);
    }
    
    /**
     * Judge whether service has instance.
     *
     * @return true if no instance, otherwise false
     */
    public boolean isEmpty() {
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            final Cluster cluster = entry.getValue();
            if (!cluster.isEmpty()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get all instance.
     *
     * @return list of all instance
     */
    public List<Instance> allIPs() {
        List<Instance> result = new ArrayList<>();
        // 遍历当前service所包含的所有cLuster
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            // 将当前遍历cLuster 中包含的所有instance添加到result
            result.addAll(entry.getValue().allIPs());
        }
        
        return result;
    }
    
    /**
     * Get all instance of ephemeral or consistency.
     *
     * @param ephemeral whether ephemeral instance
     * @return all instance of ephemeral if @param ephemeral = true, otherwise all instance of consistency
     */
    public List<Instance> allIPs(boolean ephemeral) {
        List<Instance> result = new ArrayList<>();
        for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
            result.addAll(entry.getValue().allIPs(ephemeral));
        }
        
        return result;
    }
    
    /**
     * Get all instance from input clusters. 从输入集群中获取所有实例
     *
     * @param clusters cluster names
     * @return all instance from input clusters.
     */
    public List<Instance> allIPs(List<String> clusters) {
        List<Instance> result = new ArrayList<>();
        for (String cluster : clusters) {
            Cluster clusterObj = clusterMap.get(cluster);
            if (clusterObj == null) {
                continue;
            }
            // 将当前遍历cLuster的所有instance添加到resuLt集合
            // 包含所有持久实例与临时实例
            result.addAll(clusterObj.allIPs());
        }
        return result;
    }
    
    /**
     * Get all instance from input clusters.
     *
     * @param clusters cluster names
     * @return all instance from input clusters, if clusters is empty, return all cluster
     */
    public List<Instance> srvIPs(List<String> clusters) {
        if (CollectionUtils.isEmpty(clusters)) {
            clusters = new ArrayList<>();
            clusters.addAll(clusterMap.keySet());
        }
        // 获取到当前服务的所有cluster 中的所有instance
        return allIPs(clusters);
    }
    
    public String toJson() {
        return JacksonUtils.toJson(this);
    }
    
    @JsonIgnore
    public String getServiceString() {
        Map<Object, Object> serviceObject = new HashMap<Object, Object>(10);
        Service service = this;
        
        serviceObject.put("name", service.getName());
        
        List<Instance> ips = service.allIPs();
        int invalidIpCount = 0;
        int ipCount = 0;
        for (Instance ip : ips) {
            if (!ip.isHealthy()) {
                invalidIpCount++;
            }
            
            ipCount++;
        }
        
        serviceObject.put("ipCount", ipCount);
        serviceObject.put("invalidIPCount", invalidIpCount);
        
        serviceObject.put("owners", service.getOwners());
        serviceObject.put("token", service.getToken());
        
        serviceObject.put("protectThreshold", service.getProtectThreshold());
        
        List<Object> clustersList = new ArrayList<Object>();
        // 遍历当前service的所有cLuster
        for (Map.Entry<String, Cluster> entry : service.getClusterMap().entrySet()) {
            Cluster cluster = entry.getValue();
            // 将当前遍历cLuster的描述信息写入到cLusters集合
            Map<Object, Object> clusters = new HashMap<Object, Object>(10);
            clusters.put("name", cluster.getName());
            clusters.put("healthChecker", cluster.getHealthChecker());
            clusters.put("defCkport", cluster.getDefCkport());
            clusters.put("defIPPort", cluster.getDefIPPort());
            clusters.put("useIPPort4Check", cluster.isUseIPPort4Check());
            clusters.put("sitegroup", cluster.getSitegroup());
            // 将当前遍历cLuster数据写入到cLustersList
            clustersList.add(clusters);
        }
        // 将当前service所包含的所有cLuster信息写入到map
        serviceObject.put("clusters", clustersList);
        
        try {
            return JacksonUtils.toJson(serviceObject);
        } catch (Exception e) {
            throw new RuntimeException("Service toJson failed", e);
        }
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public List<String> getOwners() {
        return owners;
    }
    
    public void setOwners(List<String> owners) {
        this.owners = owners;
    }
    
    public Map<String, Cluster> getClusterMap() {
        return clusterMap;
    }
    
    public void setClusterMap(Map<String, Cluster> clusterMap) {
        this.clusterMap = clusterMap;
    }
    
    public String getNamespaceId() {
        return namespaceId;
    }
    
    public void setNamespaceId(String namespaceId) {
        this.namespaceId = namespaceId;
    }
    
    /**
     * Update from other service.
     *
     * @param vDom other service
     */
    public void update(Service vDom) {
        
        if (!StringUtils.equals(token, vDom.getToken())) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, token: {} -> {}", getName(), token, vDom.getToken());
            token = vDom.getToken();
        }
        
        if (!ListUtils.isEqualList(owners, vDom.getOwners())) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, owners: {} -> {}", getName(), owners, vDom.getOwners());
            owners = vDom.getOwners();
        }
        
        if (getProtectThreshold() != vDom.getProtectThreshold()) {
            Loggers.SRV_LOG
                    .info("[SERVICE-UPDATE] service: {}, protectThreshold: {} -> {}", getName(), getProtectThreshold(),
                            vDom.getProtectThreshold());
            setProtectThreshold(vDom.getProtectThreshold());
        }
        
        if (resetWeight != vDom.getResetWeight().booleanValue()) {
            Loggers.SRV_LOG.info("[SERVICE-UPDATE] service: {}, resetWeight: {} -> {}", getName(), resetWeight,
                    vDom.getResetWeight());
            resetWeight = vDom.getResetWeight();
        }
        
        if (enabled != vDom.getEnabled().booleanValue()) {
            Loggers.SRV_LOG
                    .info("[SERVICE-UPDATE] service: {}, enabled: {} -> {}", getName(), enabled, vDom.getEnabled());
            enabled = vDom.getEnabled();
        }
        
        selector = vDom.getSelector();
        
        setMetadata(vDom.getMetadata());
        
        updateOrAddCluster(vDom.getClusterMap().values());
        remvDeadClusters(this, vDom);
        
        Loggers.SRV_LOG.info("cluster size, new: {}, old: {}", getClusterMap().size(), vDom.getClusterMap().size());
        
        recalculateChecksum();
    }
    
    @Override
    public String getChecksum() {
        if (StringUtils.isEmpty(checksum)) {
            recalculateChecksum();
        }
        
        return checksum;
    }
    
    /**
     * Re-calculate checksum of service. 重新计算服务校验和
     */
    public synchronized void recalculateChecksum() {
        // 获取当前service所包含的所有instance列表
        List<Instance> ips = allIPs();
        
        StringBuilder ipsString = new StringBuilder();
        // 将service所数据追加到ipsString
        ipsString.append(getServiceString());
        
        if (Loggers.SRV_LOG.isDebugEnabled()) {
            Loggers.SRV_LOG.debug("service to json: " + getServiceString());
        }
        
        if (CollectionUtils.isNotEmpty(ips)) {
            Collections.sort(ips);
        }
        // 遍历所有instances，将它们的数据进行追加
        for (Instance ip : ips) {
            String string = ip.getIp() + ":" + ip.getPort() + "_" + ip.getWeight() + "_" + ip.isHealthy() + "_" + ip
                    .getClusterName();
            ipsString.append(string);
            ipsString.append(",");
        }
        // 最终获取到当前service的所有数据，经MD5加密后赋值给checksum
        checksum = MD5Utils.md5Hex(ipsString.toString(), Constants.ENCODE);
    }
    
    private void updateOrAddCluster(Collection<Cluster> clusters) {
        for (Cluster cluster : clusters) {
            Cluster oldCluster = clusterMap.get(cluster.getName());
            if (oldCluster != null) {
                oldCluster.setService(this);
                oldCluster.update(cluster);
            } else {
                cluster.init();
                cluster.setService(this);
                clusterMap.put(cluster.getName(), cluster);
            }
        }
    }
    
    private void remvDeadClusters(Service oldDom, Service newDom) {
        Collection<Cluster> oldClusters = oldDom.getClusterMap().values();
        Collection<Cluster> newClusters = newDom.getClusterMap().values();
        List<Cluster> deadClusters = (List<Cluster>) CollectionUtils.subtract(oldClusters, newClusters);
        for (Cluster cluster : deadClusters) {
            oldDom.getClusterMap().remove(cluster.getName());
            
            cluster.destroy();
        }
    }
    
    public int getFinalizeCount() {
        return finalizeCount;
    }
    
    public void setFinalizeCount(int finalizeCount) {
        this.finalizeCount = finalizeCount;
    }
    
    public void addCluster(Cluster cluster) {
        clusterMap.put(cluster.getName(), cluster);
    }
    
    /**
     * Judge whether service is validate.
     *
     * @throws IllegalArgumentException if service is not validate
     */
    public void validate() {
        if (!getName().matches(SERVICE_NAME_SYNTAX)) {
            throw new IllegalArgumentException(
                    "dom name can only have these characters: 0-9a-zA-Z-._:, current: " + getName());
        }
        for (Cluster cluster : clusterMap.values()) {
            cluster.validate();
        }
    }
}

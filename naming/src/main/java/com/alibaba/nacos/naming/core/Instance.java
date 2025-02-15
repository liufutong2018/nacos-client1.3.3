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
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.naming.healthcheck.HealthCheckStatus;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IP under service.
 * 就代表一个Nacos Client主机
 * @author nkorange
 */
@JsonInclude(Include.NON_NULL)
public class Instance extends com.alibaba.nacos.api.naming.pojo.Instance implements Comparable {
    
    private static final double MAX_WEIGHT_VALUE = 10000.0D;
    
    private static final double MIN_POSITIVE_WEIGHT_VALUE = 0.01D;
    
    private static final double MIN_WEIGHT_VALUE = 0.00D;
    
    private volatile long lastBeat = System.currentTimeMillis();
    
    @JsonIgnore
    private volatile boolean mockValid = false;
    
    // 对于持久实例，其值为true，则表示当前实例状态为”不健康”，即当前实例被“标记”
    // 对于临时实例，其值永久为默认值false，即该属性对临时实例没有意义
    private volatile boolean marked = false;
    
    private String tenant;
    
    private String app;
    
    private static final Pattern IP_PATTERN = Pattern
            .compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):?(\\d{1,5})?");
    
    private static final Pattern ONLY_DIGIT_AND_DOT = Pattern.compile("(\\d|\\.)+");
    
    private static final String SPLITER = "_";
    
    public Instance() {
    }
    
    public boolean isMockValid() {
        return mockValid;
    }
    
    public void setMockValid(boolean mockValid) {
        this.mockValid = mockValid;
    }
    
    public long getLastBeat() {
        return lastBeat;
    }
    
    public void setLastBeat(long lastBeat) {
        this.lastBeat = lastBeat;
    }
    
    public Instance(String ip, int port) {
        this.setIp(ip);
        this.setPort(port);
        this.setClusterName(UtilsAndCommons.DEFAULT_CLUSTER_NAME);
    }
    
    public Instance(String ip, int port, String clusterName) {
        this.setIp(ip.trim());
        this.setPort(port);
        this.setClusterName(clusterName);
    }
    
    public Instance(String ip, int port, String clusterName, String tenant, String app) {
        this.setIp(ip.trim());
        this.setPort(port);
        this.setClusterName(clusterName);
        this.tenant = tenant;
        this.app = app;
    }
    
    /**
     * Create {@link Instance} from encoding string.
     *
     * @param config instance encoding string
     * @return new Instance
     */
    public static Instance fromString(String config) {
        String[] ipAddressAttributes = config.split(SPLITER);
        if (ipAddressAttributes.length < 1) {
            return null;
        }
        
        String provider = ipAddressAttributes[0];
        Matcher matcher = IP_PATTERN.matcher(provider);
        if (!matcher.matches()) {
            return null;
        }
        
        int expectedGroupCount = 2;
        
        int port = 0;
        if (NumberUtils.isNumber(matcher.group(expectedGroupCount))) {
            port = Integer.parseInt(matcher.group(expectedGroupCount));
        }
        
        Instance instance = new Instance(matcher.group(1), port);
        
        // 7 possible formats of config:
        // ip:port
        // ip:port_weight
        // ip:port_weight_cluster
        // ip:port_weight_valid
        // ip:port_weight_valid_cluster
        // ip:port_weight_valid_marked
        // ip:port_weight_valid_marked_cluster
        int minimumLength = 1;
        
        if (ipAddressAttributes.length > minimumLength) {
            // determine 'weight':
            instance.setWeight(NumberUtils.toDouble(ipAddressAttributes[minimumLength], 1));
        }
        
        minimumLength++;
        
        if (ipAddressAttributes.length > minimumLength) {
            // determine 'valid':
            if (Boolean.TRUE.toString().equals(ipAddressAttributes[minimumLength]) || Boolean.FALSE.toString()
                    .equals(ipAddressAttributes[minimumLength])) {
                instance.setHealthy(Boolean.parseBoolean(ipAddressAttributes[minimumLength]));
            }
            
            // determine 'cluster':
            if (!Boolean.TRUE.toString().equals(ipAddressAttributes[ipAddressAttributes.length - 1]) && !Boolean.FALSE
                    .toString().equals(ipAddressAttributes[ipAddressAttributes.length - 1])) {
                instance.setClusterName(ipAddressAttributes[ipAddressAttributes.length - 1]);
            }
        }
        
        minimumLength++;
        
        if (ipAddressAttributes.length > minimumLength) {
            // determine 'marked':
            if (Boolean.TRUE.toString().equals(ipAddressAttributes[minimumLength]) || Boolean.FALSE.toString()
                    .equals(ipAddressAttributes[minimumLength])) {
                instance.setMarked(Boolean.parseBoolean(ipAddressAttributes[minimumLength]));
            }
        }
        
        return instance;
    }
    
    public String toIpAddr() {
        return getIp() + ":" + getPort();
    }
    
    @Override
    public String toString() {
        return getDatumKey() + SPLITER + getWeight() + SPLITER + isHealthy() + SPLITER + marked + SPLITER
                + getClusterName();
    }
    
    /**
     * Serialize to Json.
     *
     * @return json string
     */
    public String toJson() {
        return JacksonUtils.toJson(this);
    }
    
    /**
     * Create {@link Instance} from json string.
     *
     * @param json json string
     * @return new Instance
     */
    public static Instance fromJson(String json) {
        Instance ip;
        
        try {
            ip = JacksonUtils.toObj(json, Instance.class);
        } catch (Exception e) {
            ip = fromString(json);
        }
        
        if (ip == null) {
            throw new IllegalArgumentException("malformed ip config: " + json);
        }
        
        if (ip.getWeight() > MAX_WEIGHT_VALUE) {
            ip.setWeight(MAX_WEIGHT_VALUE);
        }
        
        if (ip.getWeight() < MIN_POSITIVE_WEIGHT_VALUE && ip.getWeight() > MIN_WEIGHT_VALUE) {
            ip.setWeight(MIN_POSITIVE_WEIGHT_VALUE);
        } else if (ip.getWeight() < MIN_WEIGHT_VALUE) {
            ip.setWeight(0.0D);
        }
        
        try {
            ip.validate();
        } catch (NacosException e) {
            throw new IllegalArgumentException("malformed ip config: " + json);
        }
        
        return ip;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Instance other = (Instance) obj;
        
        // 0 means wild
        return getIp().equals(other.getIp()) && (getPort() == other.getPort() || getPort() == 0)
                && this.isEphemeral() == other.isEphemeral();
    }
    
    @JsonIgnore
    public String getDatumKey() {
        if (getPort() > 0) {
            return getIp() + ":" + getPort() + ":" + UtilsAndCommons.LOCALHOST_SITE + ":" + getClusterName();
        } else {
            return getIp() + ":" + UtilsAndCommons.LOCALHOST_SITE + ":" + getClusterName();
        }
    }
    
    @JsonIgnore
    public String getDefaultKey() {
        if (getPort() > 0) {
            return getIp() + ":" + getPort() + ":" + UtilsAndCommons.UNKNOWN_SITE;
        } else {
            return getIp() + ":" + UtilsAndCommons.UNKNOWN_SITE;
        }
    }
    
    @Override
    public int hashCode() {
        return getIp().hashCode();
    }
    
    public void setBeingChecked(boolean isBeingChecked) {
        HealthCheckStatus.get(this).isBeingChecked.set(isBeingChecked);
    }
    
    public boolean markChecking() {
        return HealthCheckStatus.get(this).isBeingChecked.compareAndSet(false, true);
    }
    
    @JsonIgnore
    public long getCheckRt() {
        return HealthCheckStatus.get(this).checkRt;
    }
    
    @JsonIgnore
    public AtomicInteger getOkCount() {
        return HealthCheckStatus.get(this).checkOkCount;
    }
    
    @JsonIgnore
    public AtomicInteger getFailCount() {
        return HealthCheckStatus.get(this).checkFailCount;
    }
    
    @JsonIgnore
    public void setCheckRt(long checkRt) {
        HealthCheckStatus.get(this).checkRt = checkRt;
    }
    
    public boolean isMarked() {
        return marked;
    }
    
    public void setMarked(boolean marked) {
        this.marked = marked;
    }
    
    public String getApp() {
        return app;
    }
    
    public void setApp(String app) {
        this.app = app;
    }
    
    public String getTenant() {
        return tenant;
    }
    
    public void setTenant(String tenant) {
        this.tenant = tenant;
    }
    
    public String generateInstanceId() {
        return getIp() + "#" + getPort() + "#" + getClusterName() + "#" + getServiceName();
    }
    
    /**
     * Generate instance id.
     *
     * @param currentInstanceIds current instance ids
     * @return new instance id
     */
    public String generateInstanceId(Set<String> currentInstanceIds) {
        String instanceIdGenerator = getInstanceIdGenerator();
        if (Constants.SNOWFLAKE_INSTANCE_ID_GENERATOR.equalsIgnoreCase(instanceIdGenerator)) {
            return generateSnowflakeInstanceId(currentInstanceIds);
        } else {
            return generateInstanceId();
        }
    }
    
    private String generateSnowflakeInstanceId(Set<String> currentInstanceIds) {
        int id = 0;
        while (currentInstanceIds.contains(String.valueOf(id))) {
            id++;
        }
        String idStr = String.valueOf(id);
        currentInstanceIds.add(idStr);
        return idStr;
    }
    
    /**
     * Judge whether this instance is validate.
     *
     * @throws NacosException if instance is not validate
     */
    public void validate() throws NacosException {
        if (onlyContainsDigitAndDot()) {
            Matcher matcher = IP_PATTERN.matcher(getIp() + ":" + getPort());
            if (!matcher.matches()) {
                throw new NacosException(NacosException.INVALID_PARAM,
                        "instance format invalid: Your IP address is spelled incorrectly");
            }
        }
        
        if (getWeight() > MAX_WEIGHT_VALUE || getWeight() < MIN_WEIGHT_VALUE) {
            throw new NacosException(NacosException.INVALID_PARAM,
                    "instance format invalid: The weights range from " + MIN_WEIGHT_VALUE + " to " + MAX_WEIGHT_VALUE);
        }
        
    }
    
    private boolean onlyContainsDigitAndDot() {
        Matcher matcher = ONLY_DIGIT_AND_DOT.matcher(getIp());
        return matcher.matches();
    }
    
    @Override
    public int compareTo(Object o) {
        if (!(o instanceof Instance)) {
            Loggers.SRV_LOG.error("[INSTANCE-COMPARE] Object is not an instance of IPAdress, object: {}", o.getClass());
            throw new IllegalArgumentException("Object is not an instance of IPAdress,object: " + o.getClass());
        }
        
        Instance instance = (Instance) o;
        String ipKey = instance.toString();
        
        return this.toString().compareTo(ipKey);
    }
}

package com.simple.schedule.config;

import com.alibaba.fastjson.JSON;
import com.simple.schedule.annotation.DcsScheduled;
import com.simple.schedule.common.Constants;
import com.simple.schedule.domain.ExecOrder;
import com.simple.schedule.service.HeartbeatService;
import com.simple.schedule.service.ZkCuratorServer;
import com.simple.schedule.task.CronTaskRegister;
import com.simple.schedule.task.SchedulingRunnable;
import com.simple.schedule.util.StrUtil;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.simple.schedule.common.Constants.Global.*;



public class DcsSchedulingConfiguration implements ApplicationContextAware, BeanPostProcessor, ApplicationListener<ContextRefreshedEvent> {

    private Logger logger = LoggerFactory.getLogger(DcsSchedulingConfiguration.class);

    private final Set<Class<?>> nonAnnotatedClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(64));

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        Constants.Global.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        if (this.nonAnnotatedClasses.contains(targetClass)) {
            return bean;
        }
        Method[] methods = ReflectionUtils.getAllDeclaredMethods(bean.getClass());
        for (Method method : methods) {
            DcsScheduled dcsScheduled = AnnotationUtils.findAnnotation(method, DcsScheduled.class);
            if (null == dcsScheduled || 0 == method.getDeclaredAnnotations().length) {
                continue;
            }
            //??????beanName -> ArrayList,???????????????ArrayList
            List<ExecOrder> execOrderList = Constants.execOrderMap.computeIfAbsent(beanName, k -> new ArrayList<>());
            ExecOrder execOrder = new ExecOrder();
            execOrder.setBean(bean);
            execOrder.setBeanName(beanName);
            execOrder.setMethodName(method.getName());
            execOrder.setDesc(dcsScheduled.desc());
            execOrder.setCron(dcsScheduled.cron());
            execOrder.setAutoStartup(dcsScheduled.autoStartup());
            execOrderList.add(execOrder);
            this.nonAnnotatedClasses.add(targetClass);
        }
        return bean;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        try {
            ApplicationContext applicationContext = contextRefreshedEvent.getApplicationContext();
            //1. ???????????????
            init_config(applicationContext);
            //2. ???????????????
            init_server(applicationContext);
            //3. ????????????
            init_task(applicationContext);
            //4. ????????????
            init_node();
            //5. ????????????
            HeartbeatService.getInstance().startFlushScheduleStatus();
            logger.info("middleware schedule init config???server???task???node???heart done!");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    //1.???????????????
    private void init_config(ApplicationContext applicationContext) {
        try {
            StarterServiceProperties properties = applicationContext.getBean("simple-schedule-starterAutoConfig", StarterAutoConfig.class).getProperties();
            Constants.Global.zkAddress = properties.getZkAddress();
            Constants.Global.schedulerServerId = properties.getSchedulerServerId();
            Constants.Global.schedulerServerName = properties.getSchedulerServerName();
            InetAddress ip = InetAddress.getLocalHost();
            Constants.Global.ip = ip.getHostAddress();
        } catch (Exception e) {
            logger.error("middleware schedule init config error???", e);
            throw new RuntimeException(e);
        }
    }

    //2. ???????????????
    private void init_server(ApplicationContext applicationContext) {
        try {
            //??????zk??????
            CuratorFramework client = ZkCuratorServer.getClient(Constants.Global.zkAddress);
            //????????????
            path_root_server = StrUtil.joinStr(path_root, LINE, "server", LINE, schedulerServerId);
            path_root_server_ip = StrUtil.joinStr(path_root_server, LINE, "ip", LINE, Constants.Global.ip);
            //????????????&?????????????????????IP???????????????
            ZkCuratorServer.deletingChildrenIfNeeded(client, path_root_server_ip);
            ZkCuratorServer.createNode(client, path_root_server_ip);
            ZkCuratorServer.setData(client, path_root_server, schedulerServerName);
            //????????????&??????
            ZkCuratorServer.createNodeSimple(client, Constants.Global.path_root_exec);
            ZkCuratorServer.addTreeCacheListener(applicationContext, client, Constants.Global.path_root_exec);
        } catch (Exception e) {
            logger.error("middleware schedule init server error???", e);
            throw new RuntimeException(e);
        }
    }

    //3. ????????????
    private void init_task(ApplicationContext applicationContext) {
        CronTaskRegister cronTaskRegistrar = applicationContext.getBean("simple-schedule-cronTaskRegister", CronTaskRegister.class);
        Set<String> beanNames = Constants.execOrderMap.keySet();
        for (String beanName : beanNames) {
            List<ExecOrder> execOrderList = Constants.execOrderMap.get(beanName);
            for (ExecOrder execOrder : execOrderList) {
                if (!execOrder.getAutoStartup()) continue;
                SchedulingRunnable task = new SchedulingRunnable(execOrder.getBean(), execOrder.getBeanName(), execOrder.getMethodName());
                cronTaskRegistrar.addCronTask(task, execOrder.getCron());
            }
        }
    }

    //4. ????????????
    private void init_node() throws Exception {
        Set<String> beanNames = Constants.execOrderMap.keySet();
        for (String beanName : beanNames) {
            List<ExecOrder> execOrderList = Constants.execOrderMap.get(beanName);
            for (ExecOrder execOrder : execOrderList) {
                String path_root_server_ip_clazz = StrUtil.joinStr(path_root_server_ip, LINE, "clazz", LINE, execOrder.getBeanName());
                String path_root_server_ip_clazz_method = StrUtil.joinStr(path_root_server_ip_clazz, LINE, "method", LINE, execOrder.getMethodName());
                String path_root_server_ip_clazz_method_status = StrUtil.joinStr(path_root_server_ip_clazz, LINE, "method", LINE, execOrder.getMethodName(), "/status");
                //????????????
                ZkCuratorServer.createNodeSimple(client, path_root_server_ip_clazz);
                ZkCuratorServer.createNodeSimple(client, path_root_server_ip_clazz_method);
                ZkCuratorServer.createNodeSimple(client, path_root_server_ip_clazz_method_status);
                //??????????????????[??????]
                ZkCuratorServer.appendPersistentData(client, path_root_server_ip_clazz_method + "/value", JSON.toJSONString(execOrder));
                //??????????????????[??????]
                ZkCuratorServer.setData(client, path_root_server_ip_clazz_method_status, execOrder.getAutoStartup() ? "1" : "0");
            }
        }

    }

}

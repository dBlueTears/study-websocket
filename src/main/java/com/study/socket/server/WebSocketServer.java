package com.study.socket.server;


import com.alibaba.fastjson.JSON;
import com.study.socket.utils.SpringUtils;
import com.study.socket.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.validation.constraints.NotNull;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket服务类
 */
@Slf4j
@Component
@ServerEndpoint("/websocket/{userId}")
public class WebSocketServer {

    private static final long sessionTimeout = 600000;

    /**
     * 当前在线连接数
     */
    private static AtomicInteger onlineCount = new AtomicInteger(0);

    /**
     * 存放同连接 对应的多个客户端的连接数量
     */
    private static ConcurrentHashMap<String, Integer> count = new ConcurrentHashMap<String, Integer>();

    /**
     * 存放每个客户端对应的 WebSocketServer 对象
     * String  链接的标识
     * CopyOnWriteArraySet<WebSocketServer> 存放相同连接的多个对应的客户端
     */
    private static ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketServer>> webSocketMap = new ConcurrentHashMap<>();

    /**
     * 与某个客户端的连接会话，需要通过它来给客户端发送数据
     */
    private Session session;

    /**
     * 接收的标识信息
     */
    private String userId;


    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        session.setMaxIdleTimeout(sessionTimeout);
        this.session = session;
        this.userId = userId;
        if (exitUser(userId)) {
            CopyOnWriteArraySet<WebSocketServer> webSocketSet = webSocketMap.get(userId);
            //从set中增加
            webSocketSet.add(this);
            //在线数加1
            userCountIncrease(userId);
        } else {
            initUserInfo(userId);
        }
        try {
            sendMessage("欢迎" + userId + "加入连接！");
        } catch (IOException e) {
            log.error(userId + ",网络异常!!!!!!");
        }
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        CopyOnWriteArraySet<WebSocketServer> webSocketSet = webSocketMap.get(userId);
        //从set中删除
        webSocketSet.remove(this);
        //在线数减1
        userCountDecrement(userId);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("接收客户端消息:" + userId + ",报文:" + message);
        CopyOnWriteArraySet<WebSocketServer> webSocketSet = webSocketMap.get(userId);
        for (WebSocketServer item : webSocketSet) {
            try {
                item.sendMessage(message);
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
        }
    }

    /**
     * 发生错误时调用
     *
     * @param session
     * @param error
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("错误:" + this.userId + ",原因:" + error.getMessage());
        error.printStackTrace();
    }

    /**
     * @description:  分布式  使用redis 去发布消息
     * redis广播消息
     * 指定的用户发布消息
     */
    public static void sendMessage(@NotNull String key, String message) {
        log.info("服务端发送消息:" + key + ",报文:" + message);
        String newMessage= null;
        try {
            newMessage = new String(message.getBytes(Constants.UTF8), Constants.UTF8);
        } catch (UnsupportedEncodingException e) {
            log.info("sendMessageByRedis exception:" + e);
        }
        Map<String,String> map = new HashMap<String, String>();
        map.put(Constants.REDIS_MESSAGE_KEY, key);
        map.put(Constants.REDIS_MESSAGE_VALUE, newMessage);
        //广播
        StringRedisTemplate template = SpringUtils.getBean(StringRedisTemplate.class);
        template.convertAndSend(Constants.REDIS_CHANNEL, JSON.toJSONString(map));
    }

    /**
     * 多用户发送
     * @param userIds
     * @param message
     */
    public static void sendMessageByUsers(String[] userIds, String message) {
        for (String userId : userIds) {
            sendMessage(userId, message);
        }
    }

    /**
     * @description: 单机使用  外部接口通过指定的客户id向该客户推送消息。
     */
    public static void sendMessageByWayBillId(@NotNull String key, String message) {
        if (webSocketMap.containsKey(key)) {
            // 获取 userId 对应的所有 WebSocketServer 实例
            CopyOnWriteArraySet<WebSocketServer> servers = webSocketMap.get(key);
            for (WebSocketServer server : servers) {
                if (ObjectUtils.isNotEmpty(server)) {
                    try {
                        server.sendMessage(message);
                        log.info(key+"发送消息："+message);
                    } catch (IOException e) {
                        e.printStackTrace();
                        log.error(key+"发送消息失败");
                    }
                } else {
                    log.error(key+"未连接");
                }
            }
        }
    }

    /**
     * 实现服务器主动推送
     */
    public void sendMessage(String message) throws IOException {
        this.session.getBasicRemote().sendText(message);
    }


    /**
     * 在线连接+1和相同连接下的在线数+1
     * @param userId
     */
    public void userCountIncrease(String userId) {
        addOnlineCount();
        log.info(userId + "连接,当前在线数为:" + getOnlineCount());
        if (count.containsKey(userId)) {
            count.put(userId, count.get(userId) + 1);
        } else {
            count.put(userId, 1);
        }
        log.info(userId + "连接,相同连接下客户端数为:" + getCount(userId));

    }

    /**
     * 在线连接-1和相同连接下的在线数-1
     * @param userId
     */
    public void userCountDecrement(String userId) {
        subOnlineCount();
        log.info(userId +"退出,当前在线人数为:" + getOnlineCount());
        if (count.containsKey(userId)) {
            count.put(userId, count.get(userId) - 1);
        }
        log.info(userId + "退出,相同连接下客户端数为:" + getCount(userId));
    }

    /**
     * 获取相同连接的客户端数
     * @param userId
     */
    public Integer getCount(String userId) {
        Integer sum = 0;
        if (count.containsKey(userId)) {
            sum = count.get(userId);
        }
        return sum;
    }

    /**
     * 初始化相同连接的客户端对象
     * @param userId
     */
    private void initUserInfo(String userId) {
        //初始化对象
        CopyOnWriteArraySet<WebSocketServer> webSocketSet = new CopyOnWriteArraySet<>();
        //从set中增加
        webSocketSet.add(this);
        webSocketMap.put(userId, webSocketSet);
        //在线数加1
        userCountIncrease(userId);
    }


    public static synchronized AtomicInteger getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        WebSocketServer.onlineCount.getAndIncrement();
    }

    public static synchronized void subOnlineCount() {
        WebSocketServer.onlineCount.getAndDecrement();
    }

    public boolean exitUser(String userId) {
        return webSocketMap.containsKey(userId);
    }
}

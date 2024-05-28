package com.study.socket.controller;

import com.study.socket.server.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class SocketController {

    @Autowired
    private WebSocketServer webSocketServer;

    @GetMapping("/info")
    public String sendInfo(@RequestParam("userId") String userId, @RequestParam("message")  String message) throws IOException {
        webSocketServer.sendMessage(userId, message);
        return message;
    }

}

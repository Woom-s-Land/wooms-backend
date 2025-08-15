package com.ee06.wooms.domain.chat.controller;

import com.ee06.wooms.domain.chat.ChannelRepository;
import com.ee06.wooms.domain.chat.dto.ChatMessage;
import com.ee06.wooms.domain.chat.dto.MoveMessage;
import com.ee06.wooms.domain.chat.entity.Channel;
import com.ee06.wooms.domain.chat.entity.Woom;
import com.ee06.wooms.global.common.CommonResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
public class ChatController {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate template;
    private final ChannelRepository channelRepository;

//    @PostMapping("/api/join/{woomsId}")
//    public CommonResponse join(@PathVariable("woomsId") UUID woomsId) {
//        Channel channel = channelRepository.get(woomsId);
//        channel.getWooms().forEach(woom1 ->
//                {
//                    try {
//                        log.info("woom : {} ", MoveMessage.of(woom1));
//                        template.convertAndSend("/ws/wooms/move/" + woomsId, objectMapper.writeValueAsString(MoveMessage.of(woom1)));
//                    } catch (JsonProcessingException e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//        );
//        return new CommonResponse("ok");
//    }

    @MessageMapping("/chat/{woomsId}")
    public void sendMessage(@DestinationVariable("woomsId") UUID woomsId,
                            String content) throws Exception {
        ChatMessage chatMessage = objectMapper.readValue(content, ChatMessage.class);

        log.info("chatMessage: {}", chatMessage);
        template.convertAndSend("/ws/wooms/chat/" + woomsId, objectMapper.writeValueAsString(chatMessage));
    }

    @MessageMapping("/move/{woomsId}")
    public void move(@DestinationVariable("woomsId") UUID woomsId,
                     String content) throws Exception {
        MoveMessage moveMessage = objectMapper.readValue(content, MoveMessage.class);

        channelRepository.get(woomsId).moveWoom(new Woom(moveMessage.getNickname(), moveMessage.getCostume(), woomsId), moveMessage);
        log.info("moveMessage: {}", moveMessage);
        template.convertAndSend("/ws/wooms/move/" + woomsId, objectMapper.writeValueAsString(moveMessage));
    }

}

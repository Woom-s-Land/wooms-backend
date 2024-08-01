package com.ee06.wooms.domain.stories.service;

import com.ee06.wooms.domain.stories.dto.StoryWriteDto;
import com.ee06.wooms.domain.stories.entity.Story;
import com.ee06.wooms.domain.stories.repository.StoryRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.exception.ex.UserNotFoundException;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.wooms.exception.ex.WoomsNotValidException;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import com.ee06.wooms.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class StoryService {
    private final StoryRepository storyRepository;
    private final UserRepository userRepository;
    private final WoomsRepository woomsRepository;

    public CommonResponse writeStory(CustomUserDetails userDetails, StoryWriteDto writeDto, Long woomsId) {
        storyRepository.save(Story.builder()
                .user(fetchUser(userDetails.getUuid()))
                .wooms(fetchWooms(woomsId))
                .content(writeDto.getContent())
                .build());

        return new CommonResponse("ok");
    }
    private User fetchUser(String userUuid) {
        return userRepository.findById(UUID.fromString(userUuid))
                .orElseThrow(UserNotFoundException::new);
    }

    private Wooms fetchWooms(Long woomsId) {
        return woomsRepository.findWoomsById(woomsId)
                .orElseThrow(WoomsNotValidException::new);
    }
}
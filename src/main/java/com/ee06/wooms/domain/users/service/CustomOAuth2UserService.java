package com.ee06.wooms.domain.users.service;

import com.ee06.wooms.domain.users.dto.CustomOAuth2User;
import com.ee06.wooms.domain.users.dto.GithubResponse;
import com.ee06.wooms.domain.users.dto.GoogleResponse;
import com.ee06.wooms.domain.users.dto.OAuth2Response;
import com.ee06.wooms.domain.users.entity.SocialProvider;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.entity.UserStatus;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.global.oauth.exception.NotFoundPlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuth2Response oAuth2Response =
                provideOAuth(userRequest.getClientRegistration().getRegistrationId(), oAuth2User)
                .orElseThrow(() -> new NotFoundPlatformException("제공하지 않는 플랫폼"));

        String userEmail = oAuth2Response.getEmail();

        Optional<User> existUser = userRepository.findByEmail(userEmail);
        if(existUser.isPresent()) {
            userRepository.save(existUser.get());
            return new CustomOAuth2User(existUser.get(), oAuth2User.getAttributes());
        }

        User user = setUserInfo(userEmail, oAuth2Response, registrationId);
        userRepository.save(user);
        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }

    private User setUserInfo(String userEmail, OAuth2Response oAuth2Response, String registrationId) {
        return User.builder()
                .uuid(UUID.randomUUID())
                .email(userEmail)
                .password(bCryptPasswordEncoder.encode("password"))
                .name(oAuth2Response.getName())
                .socialProvider(SocialProvider.valueOf(registrationId.toUpperCase()))
                .status(UserStatus.ACTIVE)
                .costume(1)
                .build();
    }

    private static Optional<OAuth2Response> provideOAuth(String registrationId, OAuth2User oAuth2User) {
        if(Objects.equals(registrationId, "google")) {
            return Optional.of(new GoogleResponse(oAuth2User.getAttributes()));
        }
        if(Objects.equals(registrationId, "github")) {
            return Optional.of(new GithubResponse(oAuth2User.getAttributes()));
        }
        return Optional.empty();
    }
}
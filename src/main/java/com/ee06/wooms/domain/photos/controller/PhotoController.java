package com.ee06.wooms.domain.photos.controller;

import com.ee06.wooms.domain.photos.dto.MapResponse;
import com.ee06.wooms.domain.photos.dto.PhotoDetailsResponse;
import com.ee06.wooms.domain.photos.dto.PhotoResponse;
import com.ee06.wooms.domain.photos.service.PhotoService;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/wooms/{woomsId}")
public class PhotoController {
    private final PhotoService photoService;

    @PostMapping(value = "/photos", consumes = "multipart/form-data")
    public ResponseEntity<CommonResponse> create(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                 @PathVariable("woomsId") Long woomsId,
                                                 @RequestParam("summary") String summary,
                                                 @RequestParam("mapId") Integer mapId,
                                                 @RequestParam("image") MultipartFile file) {
        return ResponseEntity.ok(photoService.save(userDetails, woomsId, summary, mapId, file));
    }

    @GetMapping("/photos/month")
    public ResponseEntity<List<PhotoResponse>> getPhotoMonth(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                            @PathVariable("woomsId") Long woomsId,
                                                            @PageableDefault(size = 6, direction = Sort.Direction.DESC, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(photoService.getMonthList(userDetails, woomsId, pageable));
    }

    @GetMapping("/photos")
    public ResponseEntity<List<PhotoResponse>> getPhotoList(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                            @PathVariable("woomsId") Long woomsId,
                                                            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
                                                            @PageableDefault(size = 6, direction = Sort.Direction.DESC, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(photoService.getPhotoList(userDetails, woomsId, date, pageable));
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<PhotoDetailsResponse> getPhotoDetail(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                               @PathVariable("woomsId") Long woomsId,
                                                               @PathVariable("photoId") Long photoId) {
        return ResponseEntity.ok(photoService.getPhotoDetails(userDetails, woomsId, photoId));
    }

    @PatchMapping("/photos/{photoId}")
    public ResponseEntity<CommonResponse> photoFlip(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @PathVariable("woomsId") Long woomsId,
                                                          @PathVariable("photoId") Long photoId) {
        return ResponseEntity.ok(photoService.photoFlip(userDetails, woomsId, photoId));
    }

    @GetMapping("/map")
    public ResponseEntity<List<MapResponse>> getMap(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                    @PathVariable("woomsId") Long woomsId){
        return ResponseEntity.ok(photoService.getMap(userDetails, woomsId));
    }
}

package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.sketch.dto.CanvasSnapshot;
import com.ee06.wooms.domain.sketch.dto.MuralResponse;
import com.ee06.wooms.domain.sketch.entity.SketchMural;
import com.ee06.wooms.domain.sketch.entity.SketchSession;
import com.ee06.wooms.domain.sketch.entity.SketchWall;
import com.ee06.wooms.domain.sketch.repository.SketchMuralRepository;
import com.ee06.wooms.domain.sketch.repository.SketchWallRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class RealtimeWallService {

    private static final int SIZE = SketchSession.SIZE;
    private static final int[][] BLANK = blankPixels();

    private final RealtimeSessionService realtimeSessionService;
    private final SketchWallRepository sketchWallRepository;
    private final SketchMuralRepository sketchMuralRepository;

    public record WallRequest(int[][] pixels) {}

    @Transactional(readOnly = true)
    public CanvasSnapshot get(CustomUserDetails current, String roomId) {
        realtimeSessionService.session(current, roomId);
        int[][] pixels = sketchWallRepository.findByRoomId(roomId)
                .map(wall -> decode(wall.getPixels()))
                .orElseGet(RealtimeWallService::blankPixels);
        return new CanvasSnapshot(SIZE, pixels);
    }

    @Transactional
    public CanvasSnapshot put(CustomUserDetails current, String roomId, WallRequest request) {
        realtimeSessionService.session(current, roomId);
        return putValidated(roomId, request);
    }

    @Transactional
    public CanvasSnapshot putFromSocket(String canonicalRoomId, WallRequest request) {
        return putValidated(canonicalRoomId, request);
    }

    private CanvasSnapshot putValidated(String roomId, WallRequest request) {
        int[][] pixels = validate(request);
        String csv = encode(pixels);
        SketchWall wall = sketchWallRepository.findByRoomId(roomId)
                .orElseGet(() -> SketchWall.builder().roomId(roomId).pixels(csv).build());
        wall.updatePixels(csv);
        sketchWallRepository.save(wall);
        return new CanvasSnapshot(SIZE, pixels);
    }

    @Transactional
    public MuralResponse archive(CustomUserDetails current, String roomId, WallRequest request) {
        RealtimeSessionService.SessionInfo session = realtimeSessionService.session(current, roomId);
        int[][] pixels = validate(request);
        if (isBlank(pixels)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot archive an empty wall");
        }

        SketchMural mural = sketchMuralRepository.save(SketchMural.builder()
                .roomId(roomId)
                .savedBy(session.nickname())
                .pixels(encode(pixels))
                .build());

        String blank = encode(blankPixels());
        SketchWall wall = sketchWallRepository.findByRoomId(roomId)
                .orElseGet(() -> SketchWall.builder().roomId(roomId).pixels(blank).build());
        wall.updatePixels(blank);
        sketchWallRepository.save(wall);
        return MuralResponse.from(mural);
    }

    private static int[][] validate(WallRequest request) {
        if (request == null || request.pixels() == null || request.pixels().length != SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pixels must be a 32x32 grid");
        }
        int[][] copy = new int[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            if (request.pixels()[row] == null || request.pixels()[row].length != SIZE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pixels must be a 32x32 grid");
            }
            for (int col = 0; col < SIZE; col++) {
                int value = request.pixels()[row][col];
                if (value < -1 || value > 11) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pixel values must be between -1 and 11");
                }
                copy[row][col] = value;
            }
        }
        return copy;
    }

    private static String encode(int[][] pixels) {
        StringBuilder csv = new StringBuilder(SIZE * SIZE * 3);
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (row != 0 || col != 0) csv.append(',');
                csv.append(pixels[row][col]);
            }
        }
        return csv.toString();
    }

    private static int[][] decode(String csv) {
        int[][] pixels = blankPixels();
        if (csv == null) return pixels;
        String[] values = csv.split(",");
        if (values.length != SIZE * SIZE) return pixels;
        try {
            for (int i = 0; i < values.length; i++) {
                int value = Integer.parseInt(values[i].trim());
                if (value < -1 || value > 11) return blankPixels();
                pixels[i / SIZE][i % SIZE] = value;
            }
            return pixels;
        } catch (NumberFormatException exception) {
            return blankPixels();
        }
    }

    private static boolean isBlank(int[][] pixels) {
        return Arrays.stream(pixels).allMatch(row -> Arrays.stream(row).allMatch(value -> value == -1));
    }

    private static int[][] blankPixels() {
        int[][] pixels = new int[SIZE][SIZE];
        for (int[] row : pixels) Arrays.fill(row, -1);
        return pixels;
    }
}

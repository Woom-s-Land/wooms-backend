package com.ee06.wooms.domain.sketchmafia.topic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One drawing prompt: the real {@code topic} (shown to citizens) and a curated
 * near-miss {@code decoy} (given to the mafia so their drawing is subtly off).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TopicPair(String category, String topic, String decoy) {
}

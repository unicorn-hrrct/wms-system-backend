package com.example.demo.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 售后与退款 API 的时间边界。
 *
 * <p>当前 PostgreSQL 表使用不带时区的时间列；API 按 v1.2 统一解释为
 * Asia/Shanghai 并输出 ISO-8601 偏移时间。反序列化兼容幂等表中已有的本地时间 JSON。</p>
 */
public final class RefundTimeJsonCodec {

    public static final ZoneId REFUND_ZONE = ZoneId.of("Asia/Shanghai");

    private RefundTimeJsonCodec() {
    }

    public static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(REFUND_ZONE).toOffsetDateTime();
    }

    public static LocalDateTime toStorageDateTime(OffsetDateTime value) {
        return value == null
            ? null
            : value.atZoneSameInstant(REFUND_ZONE).toLocalDateTime();
    }

    public static final class Serializer extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(
            LocalDateTime value,
            JsonGenerator generator,
            SerializerProvider serializers) throws IOException {
            generator.writeString(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(toOffsetDateTime(value)));
        }
    }

    public static final class Deserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(
            JsonParser parser,
            DeserializationContext context) throws IOException {
            String value = parser.getValueAsString();
            if (value == null || value.isBlank()) {
                throw InvalidFormatException.from(
                    parser, "售后时间必须是 ISO-8601 日期时间", value, LocalDateTime.class);
            }
            String normalized = value.trim();
            try {
                return toStorageDateTime(OffsetDateTime.parse(
                    normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(
                        normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException invalidTime) {
                    throw InvalidFormatException.from(
                        parser,
                        "售后时间必须是 ISO-8601 带偏移时间或兼容的历史本地时间",
                        normalized,
                        LocalDateTime.class);
                }
            }
        }
    }
}

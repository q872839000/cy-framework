package com.cy.core.serializer;

import com.cy.core.constants.Constants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 
 * Description：时间序列化为Long时间戳
 * @author 牧风
 * @date 2021年3月4日
 */
public class LocalDateTimeToLongSerializer  extends JsonSerializer<LocalDateTime>{
	@Override
    public void serialize(LocalDateTime date, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (date != null) {
            Instant instant = date.atZone(ZoneId.systemDefault()).toInstant();
            gen.writeNumber(instant.toEpochMilli());
        } else {
            gen.writeString(Constants.STRING_EMPTY);
        }
    }
}

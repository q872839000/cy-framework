package com.cy.core.serializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
/**
 * 
 * Description：时间戳转Timestamp
 * @author 牧风
 * @date 2021年3月4日
 */
public class LongToTimestampDeserializer  extends JsonDeserializer<Timestamp>{
	@Override
	public Timestamp deserialize(JsonParser p, DeserializationContext ctxt)
			throws IOException, JsonProcessingException {
		if (p != null && StringUtils.isNotBlank(p.getText())) {
			Instant instant = Instant.ofEpochMilli(Long.parseLong(p.getText()));
			return Timestamp.from(instant);
		}
		return null;
	}
}

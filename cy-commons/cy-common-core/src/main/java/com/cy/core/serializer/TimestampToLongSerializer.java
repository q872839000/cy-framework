package com.cy.core.serializer;

import com.cy.core.constants.Constants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 
 * Description：Timestamp序列化为Long时间戳
 * @author 牧风
 * @date 2021年3月4日
 */
public class TimestampToLongSerializer  extends JsonSerializer<Timestamp>{

	@Override
	public void serialize(Timestamp date, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		if (date != null) {
            Instant instant = date.toInstant();
            gen.writeNumber(instant.toEpochMilli());
        } else {
            gen.writeString(Constants.STRING_EMPTY);
        }
		
	}

    @Override
    public void serializeWithType(Timestamp value, JsonGenerator gen, SerializerProvider serializers, TypeSerializer typeSer) throws IOException {
        WritableTypeId writableTypeId = typeSer.writeTypePrefix(gen, typeSer.typeId(value, JsonToken.VALUE_STRING));
        serialize(value, gen, serializers);
        typeSer.writeTypeSuffix(gen, writableTypeId);
    }
}

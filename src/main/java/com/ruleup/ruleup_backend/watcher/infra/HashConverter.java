package com.ruleup.ruleup_backend.watcher.infra;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.HexFormat;
@Converter
public class HashConverter implements AttributeConverter<String, byte[]> {
    public byte[] convertToDatabaseColumn(String value) { return value == null ? null : HexFormat.of().parseHex(value); }
    public String convertToEntityAttribute(byte[] value) { return value == null ? null : HexFormat.of().formatHex(value); }
}

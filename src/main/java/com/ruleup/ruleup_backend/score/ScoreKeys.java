package com.ruleup.ruleup_backend.score;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
public final class ScoreKeys {
    private ScoreKeys() {}
    public static byte[] bytes(UUID id) { return id==null?null:ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    public static UUID uuid(byte[] b) { if(b==null)return null;var v=ByteBuffer.wrap(b);return new UUID(v.getLong(),v.getLong()); }
    public static String hash(Object... parts) {
        try {
            MessageDigest h=MessageDigest.getInstance("SHA-256");
            for(Object part:parts) { byte[] b=String.valueOf(part).getBytes(StandardCharsets.UTF_8);h.update(ByteBuffer.allocate(4).putInt(b.length).array());h.update(b); }
            return HexFormat.of().formatHex(h.digest());
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String source(UUID user,ScoreInput i) { return hash(user,i.kind(),i.sourceId()); }
    public static byte[] order(ScoreInput i) {
        var c=i.cycle(); var b=ByteBuffer.allocate(73);
        b.put((byte)i.kind().ordinal()); b.put(i.challengeId()==null?new byte[16]:bytes(i.challengeId()));
        b.put(c==null?new byte[16]:bytes(c.cycleId())); b.putLong(i.targetDate()==null?0:i.targetDate().toEpochDay());
        b.put(HexFormat.of().parseHex(hash(i.sourceId()))); return b.array();
    }
}

// Run against AOSP Messaging's unmodified mmslib/pdu composer; see README.md.
import com.android.messaging.mmslib.pdu.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;

public class GenerateReference {
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static PduPart part(String type, String name, String id, byte[] data, boolean utf8) {
        PduPart part = new PduPart();
        part.setContentType(bytes(type));
        part.setName(bytes(name));
        part.setContentLocation(bytes(name));
        part.setContentId(bytes(id));
        part.setData(data);
        if (utf8) part.setCharset(CharacterSets.UTF_8);
        return part;
    }
    static String smil(boolean text) {
        return "<smil><head><layout><root-layout width=\"320\" height=\"480\"/>" +
            "<region id=\"Image\" left=\"0\" top=\"0\" width=\"320\" height=\"400\" fit=\"meet\"/>" +
            "<region id=\"Text\" left=\"0\" top=\"400\" width=\"320\" height=\"80\"/>" +
            "</layout></head><body><par dur=\"5000ms\"><img src=\"photo.jpg\" region=\"Image\"/>" +
            (text ? "<text src=\"text.txt\" region=\"Text\"/>" : "") + "</par></body></smil>";
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        for (String caption : new String[] {"", "Here is the dog 🐕."}) {
            SendReq request = new SendReq();
            request.setTransactionId(bytes("mc-fixture-1"));
            request.setMmsVersion(PduHeaders.MMS_VERSION_1_2);
            request.setTo(new EncodedStringValue[] { new EncodedStringValue("+12025550198") });
            request.setMessageClass(bytes("personal"));
            request.setPriority(PduHeaders.PRIORITY_NORMAL);
            request.setDeliveryReport(PduHeaders.VALUE_NO);
            request.setReadReport(PduHeaders.VALUE_NO);
            PduBody body = new PduBody();
            body.addPart(part("application/smil", "smil.xml", "smil", bytes(smil(!caption.isEmpty())), false));
            body.addPart(part("image/jpeg", "photo.jpg", "photo", Files.readAllBytes(output.resolve("pixel.jpg")), false));
            if (!caption.isEmpty()) body.addPart(part("text/plain", "text.txt", "text", bytes(caption), true));
            request.setBody(body);
            byte[] encoded = new PduComposer(new android.content.Context(), request).make();
            if (encoded == null) throw new AssertionError("AOSP failed to compose fixture");
            Files.writeString(output.resolve(caption.isEmpty() ? "aosp-photo-only.hex" : "aosp-photo-caption.hex"), HexFormat.of().formatHex(encoded) + "\n");
        }
        StringBuilder hashes = new StringBuilder();
        for (int size : new int[] {127, 128, 16383, 16384, 262144}) {
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) data[i] = (byte)(i % 251);
            SendReq request = new SendReq();
            request.setTransactionId(bytes("mc-fixture-1"));
            request.setMmsVersion(PduHeaders.MMS_VERSION_1_2);
            request.setTo(new EncodedStringValue[] { new EncodedStringValue("+12025550198") });
            request.setMessageClass(bytes("personal"));
            request.setPriority(PduHeaders.PRIORITY_NORMAL);
            request.setDeliveryReport(PduHeaders.VALUE_NO);
            request.setReadReport(PduHeaders.VALUE_NO);
            PduBody body = new PduBody();
            body.addPart(part("application/smil", "smil.xml", "smil", bytes(smil(false)), false));
            body.addPart(part("image/jpeg", "photo.jpg", "photo", data, false));
            request.setBody(body);
            byte[] encoded = new PduComposer(new android.content.Context(), request).make();
            hashes.append(size).append(" ").append(HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(encoded))).append("\n");
        }
        Files.writeString(output.resolve("aosp-size-boundaries.sha256"), hashes);
    }
}

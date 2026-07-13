package net.easecation.ecclientlight.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LightEngineMixinStructureTest {

    private static final String CLASS_RESOURCE =
        "/net/easecation/ecclientlight/mixin/LightEngineMixin.class";

    @Test
    void engineQueuesAreAccessedWithoutMixinFieldInitializers() throws IOException {
        ClassMembers members = readClassMembers(CLASS_RESOURCE);

        assertFalse(members.fields().contains("blockNodesToCheck"));
        assertFalse(members.fields().contains("decreaseQueue"));
        assertFalse(members.fields().contains("increaseQueue"));
        assertTrue(members.methods().contains("ecclientlight$getBlockNodesToCheck"));
        assertTrue(members.methods().contains("ecclientlight$getDecreaseQueue"));
        assertTrue(members.methods().contains("ecclientlight$getIncreaseQueue"));
    }

    private static ClassMembers readClassMembers(String resource) throws IOException {
        InputStream stream = LightEngineMixinStructureTest.class.getResourceAsStream(resource);
        assertNotNull(stream);
        try (DataInputStream input = new DataInputStream(stream)) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("Invalid class file: " + resource);
            }
            input.skipNBytes(4);
            String[] utf8 = readConstantPool(input);
            input.skipNBytes(6);
            int interfaceCount = input.readUnsignedShort();
            input.skipNBytes(interfaceCount * 2L);
            Set<String> fields = readMemberNames(input, utf8);
            Set<String> methods = readMemberNames(input, utf8);
            return new ClassMembers(fields, methods);
        }
    }

    private static String[] readConstantPool(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int index = 1; index < count; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[index] = input.readUTF();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> {
                    input.skipNBytes(8);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unsupported constant-pool tag: " + tag);
            }
        }
        return utf8;
    }

    private static Set<String> readMemberNames(DataInputStream input, String[] utf8) throws IOException {
        int count = input.readUnsignedShort();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort();
            names.add(utf8[input.readUnsignedShort()]);
            input.readUnsignedShort();
            int attributeCount = input.readUnsignedShort();
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                input.readUnsignedShort();
                input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
            }
        }
        return names;
    }

    private record ClassMembers(Set<String> fields, Set<String> methods) {
    }
}

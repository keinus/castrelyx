package org.keinus.logparser.domain.input.model;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TcpFrameReaderTest {
    @Test void readsMultibyteSplitAndCoalescedFrames() throws Exception {
        String message="<134>1 한글\ninside";
        byte[] body=message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        wire.write((body.length+" ").getBytes(StandardCharsets.US_ASCII)); wire.write(body); wire.write("3 abc".getBytes());
        InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(wire.toByteArray())) {
            @Override public int read(byte[] b,int off,int len)throws IOException {return super.read(b,off,Math.min(len,1));}
        };
        var reader=new TcpFrameReader(fragmented,"octet-counted",1024);
        assertEquals(message,reader.read()); assertEquals("abc",reader.read()); assertNull(reader.read());
    }
    @Test void rejectsMalformedOversizedAndIncompleteFrames() {
        for (String wire:new String[]{"0 ","-2 ab","abc","9999999999999999999 x","12 short"})
            assertThrows(IOException.class,()->new TcpFrameReader(new ByteArrayInputStream(wire.getBytes()),"octet-counted",1024).read(),wire);
    }
    @Test void preservesLineModeAndBoundsIt()throws Exception {
        var reader=new TcpFrameReader(new ByteArrayInputStream("a\r\nb\nlast".getBytes()),"line",4);
        assertEquals("a",reader.read());assertEquals("b",reader.read());assertEquals("last",reader.read());assertNull(reader.read());
        assertThrows(IOException.class,()->new TcpFrameReader(new ByteArrayInputStream("abcde".getBytes()),"line",4).read());
    }
}

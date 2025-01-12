/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.ogg;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.OpusUtil;
import androidx.media3.extractor.VorbisUtil;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

/** {@link StreamReader} to extract Opus data out of Ogg byte stream. */
/* package */ final class OpusReader extends StreamReader {

  private static final byte[] OPUS_ID_HEADER_SIGNATURE = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
  private static final byte[] OPUS_COMMENT_HEADER_SIGNATURE = {
    'O', 'p', 'u', 's', 'T', 'a', 'g', 's'
  };

  public static boolean verifyBitstreamType(ParsableByteArray data) {
    return peekPacketStartsWith(data, OPUS_ID_HEADER_SIGNATURE);
  }

  @Override
  protected long preparePayload(ParsableByteArray packet) {
    return convertTimeToGranule(getPacketDurationUs(packet.getData()));
  }

  @Override
  @EnsuresNonNullIf(expression = "#3.format", result = false)
  protected boolean readHeaders(ParsableByteArray packet, long position, SetupData setupData)
      throws ParserException {
    if (peekPacketStartsWith(packet, OPUS_ID_HEADER_SIGNATURE)) {
      byte[] headerBytes = Arrays.copyOf(packet.getData(), packet.limit());
      int channelCount = OpusUtil.getChannelCount(headerBytes);
      List<byte[]> initializationData = OpusUtil.buildInitializationData(headerBytes);

      // The ID header must come at the start of the file:
      // https://datatracker.ietf.org/doc/html/rfc7845#section-3
      checkState(setupData.format == null);
      setupData.format =
          new Format.Builder()
              .setSampleMimeType(MimeTypes.AUDIO_OPUS)
              .setChannelCount(channelCount)
              .setSampleRate(OpusUtil.SAMPLE_RATE)
              .setInitializationData(initializationData)
              .build();
      return true;
    } else if (peekPacketStartsWith(packet, OPUS_COMMENT_HEADER_SIGNATURE)) {
      // The comment header must come immediately after the ID header, so the format will already
      // be populated: https://datatracker.ietf.org/doc/html/rfc7845#section-3
      checkStateNotNull(setupData.format);
      packet.skipBytes(OPUS_COMMENT_HEADER_SIGNATURE.length);
      VorbisUtil.CommentHeader commentHeader =
          VorbisUtil.readVorbisCommentHeader(
              packet, /* hasMetadataHeader= */ false, /* hasFramingBit= */ false);
      @Nullable
      Metadata vorbisMetadata =
          VorbisUtil.parseVorbisComments(ImmutableList.copyOf(commentHeader.comments));
      if (vorbisMetadata == null) {
        return true;
      }
      setupData.format =
          setupData
              .format
              .buildUpon()
              .setMetadata(vorbisMetadata.copyWithAppendedEntriesFrom(setupData.format.metadata))
              .build();
      return true;
    } else {
      // The ID header must come at the start of the file, so the format must already be populated:
      // https://datatracker.ietf.org/doc/html/rfc7845#section-3
      checkStateNotNull(setupData.format);
      return false;
    }
  }

  /**
   * Returns the duration of the given audio packet.
   *
   * @param packet Contains audio data.
   * @return Returns the duration of the given audio packet.
   */
  private long getPacketDurationUs(byte[] packet) {
    int toc = packet[0] & 0xFF;
    int frames;
    switch (toc & 0x3) {
      case 0:
        frames = 1;
        break;
      case 1:
      case 2:
        frames = 2;
        break;
      default:
        frames = packet[1] & 0x3F;
        break;
    }

    int config = toc >> 3;
    int length = config & 0x3;
    if (config >= 16) {
      length = 2500 << length;
    } else if (config >= 12) {
      length = 10000 << (length & 0x1);
    } else if (length == 3) {
      length = 60000;
    } else {
      length = 10000 << length;
    }
    return (long) frames * length;
  }

  /**
   * Returns true if the given {@link ParsableByteArray} starts with {@code expectedPrefix}. Does
   * not change the {@link ParsableByteArray#getPosition() position} of {@code packet}.
   *
   * @param packet The packet data.
   * @return True if the packet starts with {@code expectedPrefix}, false if not.
   */
  private static boolean peekPacketStartsWith(ParsableByteArray packet, byte[] expectedPrefix) {
    if (packet.bytesLeft() < expectedPrefix.length) {
      return false;
    }
    int startPosition = packet.getPosition();
    byte[] header = new byte[expectedPrefix.length];
    packet.readBytes(header, 0, expectedPrefix.length);
    packet.setPosition(startPosition);
    return Arrays.equals(header, expectedPrefix);
  }
}

// nn::codec::HardwareOpusDecoder and HardwareOpusMultiStreamDecoder: the real device decodes in hardware via the hwopus service (the game's audio and cutscene tracks are Opus; this is
// not an online feature). Host: each class is the SDK's software class (the sdk bundles libopus, native ARM). The constructor builds the software OpusDecoder /
// OpusMultiStreamDecoder in the same memory, and every member binds to the software class's member of the same signature (guest aliases, hle.h HLE_FORCE_ALIAS: no host code).
// Basis: each pair implements one interface. The single-stream classes' 10 vtable slots match one by one in order and signature (destructor×2, GetWorkBufferSize, IsInitialized,
// Initialize, Finalize, GetSampleRate, GetChannelCount, Reset, DecodeInterleaved); the game allocates 0x40 bytes and the software object is 0x30. The multi-stream classes' 12 match
// the same way (with GetTotalStreamCount and GetStereoStreamCount); 0x48 bytes, the software object 0x38. Each SDK defines the same member set for both classes of a pair (Super
// Mario Party's: Reset, no Decode or SetContext; OKAMI HD's: Decode and SetContext, no Reset). The hardware constructor stores its vtable, zeroes the fields and keeps the option; both destructors are a bare ret.
// Calls through the vtable reached the software members once the constructor was taken over. Calls to a member itself (an object held by value or by its own class, which the
// compiler devirtualizes) ran the hardware member on the software object: Initialize, DecodeInterleaved and GetWorkBufferSize go to the C interface below, which kept a second
// decoder state beside the object, and Finalize, IsInitialized, GetSampleRate, GetChannelCount and Reset read the hardware layout, Finalize calling through +0x08 (Super Mario
// Party's sdk+0x22ff10). Found on Just Dance 2022 (SDK 11.4.0, PR #2746) and Rayman Legends (SDK 1.3.1, PR #1540: it connected to hwopus itself at 9.5 s and aborted). The option
// argument of the hardware constructor has no software counterpart and is left in w1; C2 and D2 bind to C1 and D1 (no virtual bases: the same code), so a stripped software C2 or D2
// does not matter
#include "hle.h"

#define OPUS_WHY "hwopus service; the SDK software OpusDecoder's member of the same signature"
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoderC1Ei", "_ZN2nn5codec11OpusDecoderC1Ev", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoderC2Ei", "_ZN2nn5codec11OpusDecoderC1Ev", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoderD0Ev", "_ZN2nn5codec11OpusDecoderD0Ev", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoderD1Ev", "_ZN2nn5codec11OpusDecoderD1Ev", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoderD2Ev", "_ZN2nn5codec11OpusDecoderD1Ev", OPUS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec19HardwareOpusDecoder17GetWorkBufferSizeEii", "_ZNK2nn5codec11OpusDecoder17GetWorkBufferSizeEii", OPUS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec19HardwareOpusDecoder13IsInitializedEv", "_ZNK2nn5codec11OpusDecoder13IsInitializedEv", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder10InitializeEiiPvm", "_ZN2nn5codec11OpusDecoder10InitializeEiiPvm", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder8FinalizeEv", "_ZN2nn5codec11OpusDecoder8FinalizeEv", OPUS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec19HardwareOpusDecoder13GetSampleRateEv", "_ZNK2nn5codec11OpusDecoder13GetSampleRateEv", OPUS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec19HardwareOpusDecoder15GetChannelCountEv", "_ZNK2nn5codec11OpusDecoder15GetChannelCountEv", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder5ResetEv", "_ZN2nn5codec11OpusDecoder5ResetEv", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder17DecodeInterleavedEPmPiPsmPKvm", "_ZN2nn5codec11OpusDecoder17DecodeInterleavedEPmPiPsmPKvm", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder6DecodeEPmPiPPsimPKvm", "_ZN2nn5codec11OpusDecoder6DecodeEPmPiPPsimPKvm", OPUS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec19HardwareOpusDecoder10SetContextEPKvm", "_ZN2nn5codec11OpusDecoder10SetContextEPKvm", OPUS_WHY);
// The multi-stream class (Hyrule Warriors: Age of Calamity, SDK 9.6.0: its sound streams are multi-stream Opus; without the constructor's the game's KIDS resource loader never
// finished the first sound stream, asset/data/0x272c6efb.file, and the main thread waited for it forever at main+0x8d9bc8)
#define OPUS_MS_WHY "hwopus service; the SDK software OpusMultiStreamDecoder's member of the same signature"
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoderC1Ei", "_ZN2nn5codec22OpusMultiStreamDecoderC1Ev", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoderC2Ei", "_ZN2nn5codec22OpusMultiStreamDecoderC1Ev", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoderD0Ev", "_ZN2nn5codec22OpusMultiStreamDecoderD0Ev", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoderD1Ev", "_ZN2nn5codec22OpusMultiStreamDecoderD1Ev", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoderD2Ev", "_ZN2nn5codec22OpusMultiStreamDecoderD1Ev", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder17GetWorkBufferSizeEiiii", "_ZNK2nn5codec22OpusMultiStreamDecoder17GetWorkBufferSizeEiiii", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder13IsInitializedEv", "_ZNK2nn5codec22OpusMultiStreamDecoder13IsInitializedEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder10InitializeEiiiiPKhPvm", "_ZN2nn5codec22OpusMultiStreamDecoder10InitializeEiiiiPKhPvm", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder8FinalizeEv", "_ZN2nn5codec22OpusMultiStreamDecoder8FinalizeEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder13GetSampleRateEv", "_ZNK2nn5codec22OpusMultiStreamDecoder13GetSampleRateEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder15GetChannelCountEv", "_ZNK2nn5codec22OpusMultiStreamDecoder15GetChannelCountEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder19GetTotalStreamCountEv", "_ZNK2nn5codec22OpusMultiStreamDecoder19GetTotalStreamCountEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZNK2nn5codec30HardwareOpusMultiStreamDecoder20GetStereoStreamCountEv", "_ZNK2nn5codec22OpusMultiStreamDecoder20GetStereoStreamCountEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder5ResetEv", "_ZN2nn5codec22OpusMultiStreamDecoder5ResetEv", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder17DecodeInterleavedEPmPiPsmPKvm", "_ZN2nn5codec22OpusMultiStreamDecoder17DecodeInterleavedEPmPiPsmPKvm", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder6DecodeEPmPiPPsimPKvm", "_ZN2nn5codec22OpusMultiStreamDecoder6DecodeEPmPiPPsimPKvm", OPUS_MS_WHY);
HLE_FORCE_ALIAS("_ZN2nn5codec30HardwareOpusMultiStreamDecoder10SetContextEPKvm", "_ZN2nn5codec22OpusMultiStreamDecoder10SetContextEPKvm", OPUS_MS_WHY);

// The C interface to the same hardware decoder (Tears of the Kingdom, SDK 20.5.22: GetHardwareOpusDecoderWorkBufferSize opens hwopus and aborts in sm::Initialize).
// Each call goes to the sdk's software C interface instead. The software decoder state lives in a host block kept per game decoder object (objmap), so the size of the
// game's HardwareOpusDecoderType does not matter; the game's work buffer is sized by GetOpusDecoderWorkBufferSize and handed to the software decoder as is.
#define SOFT_OPUS_STATE 0x200   // bytes for the software OpusDecoderType (larger than the SDK's)
static void *opus_fn(const char *name) { void *f = guest_sym(name); if (!f) die("sdk lacks %s", name); return f; }
static void *opus_soft(const void *hw) { void *s = objmap_get(hw); if (!s) die("Opus decoder %p was not initialized by the host", hw); return s; }
HLE_FORCE("_ZN2nn5codec36GetHardwareOpusDecoderWorkBufferSizeEiii", "hwopus service; work buffer computed for the software decoder", size_t, h_codec_HwOpusWorkSize, int rate, int ch, int option) {
    (void)option; size_t (*f)(int, int) = opus_fn("_ZN2nn5codec28GetOpusDecoderWorkBufferSizeEii"); return f(rate, ch);
}
// SDK 11's form without the option (Big Brain Academy: Brain vs. Brain sizes its decoders with it early in nnMain, main+0x31f8e8; the SDK's own opens hwopus and aborts resolving the service)
HLE_FORCE("_ZN2nn5codec36GetHardwareOpusDecoderWorkBufferSizeEii", "hwopus service; work buffer computed for the software decoder (SDK 11 two-argument version)", size_t, h_codec_HwOpusWorkSize2,
          int rate, int ch) {
    size_t (*f)(int, int) = opus_fn("_ZN2nn5codec28GetOpusDecoderWorkBufferSizeEii"); return f(rate, ch);
}
HLE_FORCE("_ZN2nn5codec29InitializeHardwareOpusDecoderEPNS0_23HardwareOpusDecoderTypeEiiPvmi", "hwopus service; replaced by the software OpusDecoder", int, h_codec_HwOpusInit,
          void *hw, int rate, int ch, void *buf, size_t size, int option) {
    (void)option; int (*f)(void *, int, int, void *, size_t) = opus_fn("_ZN2nn5codec21InitializeOpusDecoderEPNS0_15OpusDecoderTypeEiiPvm");
    void *s = objmap_get(hw); if (!s) { s = calloc(1, SOFT_OPUS_STATE); objmap_put(hw, s); }
    return f(s, rate, ch, buf, size);
}
HLE_FORCE("_ZN2nn5codec27FinalizeHardwareOpusDecoderEPNS0_23HardwareOpusDecoderTypeE", "hwopus service; software OpusDecoder", void, h_codec_HwOpusFini, void *hw) {
    void (*f)(void *) = opus_fn("_ZN2nn5codec19FinalizeOpusDecoderEPNS0_15OpusDecoderTypeE");
    void *s = objmap_del(hw); if (s) { f(s); free(s); }
}
HLE_FORCE("_ZN2nn5codec32GetHardwareOpusDecoderSampleRateEPKNS0_23HardwareOpusDecoderTypeE", "hwopus service; software OpusDecoder", int, h_codec_HwOpusRate, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec24GetOpusDecoderSampleRateEPKNS0_15OpusDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec34GetHardwareOpusDecoderChannelCountEPKNS0_23HardwareOpusDecoderTypeE", "hwopus service; software OpusDecoder", int, h_codec_HwOpusCh, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec26GetOpusDecoderChannelCountEPKNS0_15OpusDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec24ResetHardwareOpusDecoderEPNS0_23HardwareOpusDecoderTypeE", "hwopus service; software OpusDecoder", void, h_codec_HwOpusReset, void *hw) {
    void (*f)(void *) = opus_fn("_ZN2nn5codec16ResetOpusDecoderEPNS0_15OpusDecoderTypeE"); f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec33DecodeOpusInterleavedWithHardwareEPNS0_23HardwareOpusDecoderTypeEPmPiPsmPKvm", "hwopus service; software OpusDecoder", int, h_codec_HwOpusDecode,
          void *hw, size_t *consumed, int *samples, int16_t *out, size_t out_size, const void *in, size_t in_size) {
    int (*f)(void *, size_t *, int *, int16_t *, size_t, const void *, size_t) = opus_fn("_ZN2nn5codec21DecodeOpusInterleavedEPNS0_15OpusDecoderTypeEPmPiPsmPKvm");
    return f(opus_soft(hw), consumed, samples, out, out_size, in, in_size);
}

// The multi-stream work buffer size (the class above, Hyrule Warriors: Age of Calamity)
HLE_FORCE("_ZN2nn5codec47GetHardwareOpusMultiStreamDecoderWorkBufferSizeEiiii", "hwopus service; work buffer computed for the software decoder", size_t, h_codec_HwOpusMsWorkSize,
          int rate, int ch, int streams, int stereo) {
    size_t (*f)(int, int, int, int) = opus_fn("_ZN2nn5codec39GetOpusMultiStreamDecoderWorkBufferSizeEiiii"); return f(rate, ch, streams, stereo);
}

// The C interface to the hardware multi-stream decoder (Unicorn Overlord, SDK 16.2.4: CRI's Opus streams; GetHardwareOpusMultiStreamDecoderWorkBufferSize opened hwopus and the
// game aborted in sm::Initialize at nnMain). The same swap as the single-stream C interface above: software state per game decoder object, the work buffer sized for the software decoder.
// The software OpusMultiStreamDecoderType is 0x28 bytes (InitializeOpusMultiStreamDecoder writes up to +0x24), well inside SOFT_OPUS_STATE.
HLE_FORCE("_ZN2nn5codec47GetHardwareOpusMultiStreamDecoderWorkBufferSizeEiiiii", "hwopus service; work buffer computed for the software decoder", size_t, h_codec_HwOpusMsWorkSize5,
          int rate, int ch, int streams, int stereo, int option) {
    (void)option; size_t (*f)(int, int, int, int) = opus_fn("_ZN2nn5codec39GetOpusMultiStreamDecoderWorkBufferSizeEiiii"); return f(rate, ch, streams, stereo);
}
HLE_FORCE("_ZN2nn5codec40InitializeHardwareOpusMultiStreamDecoderEPNS0_34HardwareOpusMultiStreamDecoderTypeEiiiiPKhPvmi", "hwopus service; replaced by the software OpusMultiStreamDecoder",
          int, h_codec_HwOpusMsInit, void *hw, int rate, int ch, int streams, int stereo, const uint8_t *mapping, void *buf, size_t size, int option) {
    (void)option; int (*f)(void *, int, int, int, int, const uint8_t *, void *, size_t) = opus_fn("_ZN2nn5codec32InitializeOpusMultiStreamDecoderEPNS0_26OpusMultiStreamDecoderTypeEiiiiPKhPvm");
    void *s = objmap_get(hw); if (!s) { s = calloc(1, SOFT_OPUS_STATE); objmap_put(hw, s); }
    return f(s, rate, ch, streams, stereo, mapping, buf, size);
}
HLE_FORCE("_ZN2nn5codec38FinalizeHardwareOpusMultiStreamDecoderEPNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", void, h_codec_HwOpusMsFini, void *hw) {
    void (*f)(void *) = opus_fn("_ZN2nn5codec30FinalizeOpusMultiStreamDecoderEPNS0_26OpusMultiStreamDecoderTypeE");
    void *s = objmap_del(hw); if (s) { f(s); free(s); }
}
HLE_FORCE("_ZN2nn5codec43GetHardwareOpusMultiStreamDecoderSampleRateEPKNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", int, h_codec_HwOpusMsRate, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec35GetOpusMultiStreamDecoderSampleRateEPKNS0_26OpusMultiStreamDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec45GetHardwareOpusMultiStreamDecoderChannelCountEPKNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", int, h_codec_HwOpusMsCh, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec37GetOpusMultiStreamDecoderChannelCountEPKNS0_26OpusMultiStreamDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec49GetHardwareOpusMultiStreamDecoderTotalStreamCountEPKNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", int, h_codec_HwOpusMsTotal, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec41GetOpusMultiStreamDecoderTotalStreamCountEPKNS0_26OpusMultiStreamDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec50GetHardwareOpusMultiStreamDecoderStereoStreamCountEPKNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", int, h_codec_HwOpusMsStereo, const void *hw) {
    int (*f)(const void *) = opus_fn("_ZN2nn5codec42GetOpusMultiStreamDecoderStereoStreamCountEPKNS0_26OpusMultiStreamDecoderTypeE"); return f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec35ResetHardwareOpusMultiStreamDecoderEPNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; software OpusMultiStreamDecoder", void, h_codec_HwOpusMsReset, void *hw) {
    void (*f)(void *) = opus_fn("_ZN2nn5codec27ResetOpusMultiStreamDecoderEPNS0_26OpusMultiStreamDecoderTypeE"); f(opus_soft(hw));
}
HLE_FORCE("_ZN2nn5codec44DecodeOpusMultiStreamInterleavedWithHardwareEPNS0_34HardwareOpusMultiStreamDecoderTypeEPmPiPsmPKvm", "hwopus service; software OpusMultiStreamDecoder", int, h_codec_HwOpusMsDecode,
          void *hw, size_t *consumed, int *samples, int16_t *out, size_t out_size, const void *in, size_t in_size) {
    int (*f)(void *, size_t *, int *, int16_t *, size_t, const void *, size_t) = opus_fn("_ZN2nn5codec32DecodeOpusMultiStreamInterleavedEPNS0_26OpusMultiStreamDecoderTypeEPmPiPsmPKvm");
    return f(opus_soft(hw), consumed, samples, out, out_size, in, in_size);
}
// The hardware's decode time of the last frame (nn::TimeSpan); the software decoder runs inline on the caller's thread, so none is reported
HLE_FORCE("_ZN2nn5codec47GetHardwareOpusMultiStreamDecoderProcessingTimeEPKNS0_34HardwareOpusMultiStreamDecoderTypeE", "hwopus service; no hardware decode time", int64_t, h_codec_HwOpusMsTime, const void *hw) {
    (void)hw; return 0;
}

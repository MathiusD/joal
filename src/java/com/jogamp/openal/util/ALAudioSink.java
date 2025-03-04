/**
 * Copyright 2013-2023 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 */
package com.jogamp.openal.util;

import java.nio.ByteBuffer;

import jogamp.openal.Debug;

import com.jogamp.common.ExceptionUtils;
import com.jogamp.common.av.AudioFormat;
import com.jogamp.common.av.AudioSink;
import com.jogamp.common.av.AudioSink.AudioFrame;
import com.jogamp.common.av.PTS;
import com.jogamp.common.av.TimeFrameI;
import com.jogamp.common.os.Clock;
import com.jogamp.common.util.LFRingbuffer;
import com.jogamp.common.util.PropertyAccess;
import com.jogamp.common.util.Ringbuffer;
import com.jogamp.common.util.TSPrinter;
import com.jogamp.openal.AL;
import com.jogamp.openal.ALC;
import com.jogamp.openal.ALCConstants;
import com.jogamp.openal.ALCcontext;
import com.jogamp.openal.ALCdevice;
import com.jogamp.openal.ALConstants;
import com.jogamp.openal.ALException;
import com.jogamp.openal.ALExt;
import com.jogamp.openal.ALExt.ALEVENTPROCSOFT;
import com.jogamp.openal.ALExtConstants;
import com.jogamp.openal.sound3d.AudioSystem3D;
import com.jogamp.openal.sound3d.Context;
import com.jogamp.openal.sound3d.Device;
import com.jogamp.openal.sound3d.Source;

/***
 * OpenAL {@link AudioSink} implementation.
 * <p>
 * Besides given {@link AudioSink} functionality, implementation is fully functional regarding {@link AudioFormat} and all OpenAL parameter.<br/>
 * <ul>
 * <li>All OpenAL parameter can be queried</li>
 * <li>Instance can be constructed with an OpenAL device and context, see {@link #ALAudioSink(ALCdevice, ALCcontext)}</li>
 * <li>Initialization can be performed with OpenAL paramters, see {@link #init(int, int, int, int, int, float, int, int, int)}</li>
 * </ul>
 * </p>
 */
public final class ALAudioSink implements AudioSink {
    private static final boolean DEBUG_TRACE;
    private static final TSPrinter logout;
    private static final ALC alc;
    private static final AL al;
    private static final ALExt alExt;
    private static final boolean staticsInitialized;

    private final Device device;
    private boolean hasSOFTBufferSamples;
    private boolean hasEXTMcFormats;
    private boolean hasEXTFloat32;
    private boolean hasEXTDouble;
    private boolean hasALC_thread_local_context;
    private boolean hasAL_SOFT_events;
    private boolean useAL_SOFT_events;
    private int sourceCount;
    /** default latency in [s] */
    private float defaultLatency;
    /** latency in [s] */
    private float latency;
    private final AudioFormat nativeFormat;
    private int userMaxChannels = 8;
    private AudioFormat preferredFormat;
    private final Context context;

    /** Playback speed, range [0.5 - 2.0], default 1.0. */
    private float playSpeed = 1.0f;
    private float volume = 1.0f;

    static class ALAudioFrame extends AudioFrame {
        private final int alBuffer;

        ALAudioFrame(final int alBuffer) {
            this.alBuffer = alBuffer;
        }
        public ALAudioFrame(final int alBuffer, final int pts, final int duration, final int dataSize) {
            super(pts, duration, dataSize);
            this.alBuffer = alBuffer;
        }

        /** Get this frame's OpenAL buffer name */
        public final int getALBuffer() { return alBuffer; }

        @Override
        public String toString() {
            return "ALAudioFrame[pts " + pts + " ms, l " + duration + " ms, " + byteSize + " bytes, buffer "+alBuffer+"]";
        }
    }

    private int[] alBufferNames = null;
    /** queue limit in [ms] */
    private int queueSize = 0;
    /** average frame duration in [s], initialized with latency */
    private float avgFrameDuration = 0f;

    private Ringbuffer<ALAudioFrame> alFramesFree = null;
    private Ringbuffer<ALAudioFrame> alFramesPlaying = null;
    private volatile int alBufferBytesQueued = 0;
    private volatile int last_buffered_pts = TimeFrameI.INVALID_PTS;
    private volatile boolean playRequested = false;
    private final PTS pts = new PTS( () -> { return playRequested ? playSpeed : 0f; } );
    private volatile int enqueuedFrameCount;

    private final Source alSource = new Source();
    private AudioFormat chosenFormat;
    private int alChannelLayout;
    private int alSampleType;
    private int alFormat;
    private volatile boolean available;


    static {
        Debug.initSingleton();
        DEBUG_TRACE = PropertyAccess.isPropertyDefined("joal.debug.AudioSink.trace", true);
        if( DEBUG || DEBUG_TRACE ) {
            logout = TSPrinter.stderr();
        } else {
            logout = null;
        }
        alc = AudioSystem3D.getALC();
        al = AudioSystem3D.getAL();
        alExt = AudioSystem3D.getALExt();
        staticsInitialized = AudioSystem3D.isAvailable();
    }

    /** Returns true if OpenAL has been loaded and static fields {@link ALC}, {@link AL} and {@link ALExt} have been initialized successfully, otherwise false. */
    public static boolean isInitialized() {
        return staticsInitialized;
    }

    private static Device createDevice(final String name) {
        final Device d = new Device(name);
        if( !d.isValid() ) {
            throw new ALException(getThreadName()+": ALAudioSink: Error opening OpenAL device '"+name+"'");
        }
        return d;
    }

    /**
     * Create a new instance with a new default {@link ALCdevice}
     * @throws ALException if the default {@link ALCdevice} couldn't be fully created including its context.
     */
    public ALAudioSink() throws ALException {
        this((Device)null);
    }

    /**
     * Create a new instance with a new named {@link ALCdevice}
     * @param deviceName name of
     * @throws ALException if the default {@link ALCdevice} couldn't be fully created including its context.
     */
    public ALAudioSink(final String deviceName) throws ALException {
        this(createDevice(deviceName));
    }

    /**
     * Create a new instance with an optional given {@link ALCdevice}
     *
     * @param alDevice optional OpenAL {@link Device}, a default device is opened if null.
     * @throws ALException if the default {@link ALCdevice} couldn't be fully created including its context.
     */
    public ALAudioSink(final Device alDevice) throws ALException {
        available = false;
        chosenFormat = null;

        if( !staticsInitialized ) {
            device = null;
            context = null;
            nativeFormat = DefaultFormat;
            return;
        }
        if( null == alDevice ) {
            device = createDevice(null); // default device
            if( !device.isValid() ) {
                throw new ALException(getThreadName()+": ALAudioSink: Couldn't open default device: "+device);
            }
        } else {
            device = alDevice;
            if( !device.open() ) {
                throw new ALException(getThreadName()+": ALAudioSink: Error device not open or couldn't be opened "+device);
            }
        }
        // Create audio context.
        context = new Context(device, null);
        if ( !context.isValid() ) {
            throw new ALException(getThreadName()+": ALAudioSink: Error creating OpenAL context "+context);
        }

        makeCurrent(true /* throw */);
        try {
            hasSOFTBufferSamples = al.alIsExtensionPresent(ALHelpers.AL_SOFT_buffer_samples);
            hasEXTMcFormats = al.alIsExtensionPresent(ALHelpers.AL_EXT_MCFORMATS);
            hasEXTFloat32 = al.alIsExtensionPresent(ALHelpers.AL_EXT_FLOAT32);
            hasEXTDouble = al.alIsExtensionPresent(ALHelpers.AL_EXT_DOUBLE);
            hasALC_thread_local_context = context.hasALC_thread_local_context;
            hasAL_SOFT_events = al.alIsExtensionPresent(ALHelpers.AL_SOFT_events);
            useAL_SOFT_events = hasAL_SOFT_events;

            int checkErrIter = 1;
            AudioSystem3D.checkError(device, "init."+checkErrIter++, DEBUG, false);
            int defaultSampleRate = DefaultFormat.sampleRate;
            {
                final int[] value = { 0 };
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_FREQUENCY, 1, value, 0);
                if( AudioSystem3D.checkError(device, "read ALC_FREQUENCY", DEBUG, false) || 0 == value[0] ) {
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryDefaultSampleRate: failed, using default "+defaultSampleRate);
                    }
                } else {
                    defaultSampleRate = value[0];
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryDefaultSampleRate: OK "+defaultSampleRate);
                    }
                }
                value[0] = 0;
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_MONO_SOURCES, 1, value, 0);
                if( AudioSystem3D.checkError(device, "read ALC_MONO_SOURCES", DEBUG, false) ) {
                    sourceCount = -1;
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryMonoSourceCount: failed");
                    }
                } else {
                    sourceCount = value[0];
                }
                value[0] = 0;
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_REFRESH, 1, value, 0);
                if( AudioSystem3D.checkError(device, "read ALC_FREQUENCY", DEBUG, false) || 0 == value[0] ) {
                    defaultLatency = 20f/1000f; // OpenAL-Soft default seems to be 50 Hz -> 20ms min latency
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryDefaultRefreshRate: failed");
                    }
                } else {
                    defaultLatency = 1f/value[0]; // Hz -> s
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryDefaultRefreshRate: OK "+value[0]+" Hz = "+(1000f*defaultLatency)+" ms");
                    }
                }
            }
            nativeFormat = new AudioFormat(defaultSampleRate, DefaultFormat.sampleSize, getMaxSupportedChannels(false),
                                           DefaultFormat.signed, DefaultFormat.fixedP, DefaultFormat.planar, DefaultFormat.littleEndian);
            preferredFormat = nativeFormat;
            if( DEBUG ) {
                final int[] alcvers = { 0, 0 };
                System.out.println("ALAudioSink: OpenAL Version: "+al.alGetString(ALConstants.AL_VERSION));
                System.out.println("ALAudioSink: OpenAL Extensions: "+al.alGetString(ALConstants.AL_EXTENSIONS));
                AudioSystem3D.checkError(device, "init."+checkErrIter++, DEBUG, false);
                System.out.println("ALAudioSink: Null device OpenALC:");
                alc.alcGetIntegerv(null, ALCConstants.ALC_MAJOR_VERSION, 1, alcvers, 0);
                alc.alcGetIntegerv(null, ALCConstants.ALC_MINOR_VERSION, 1, alcvers, 1);
                System.out.println("  Version: "+alcvers[0]+"."+alcvers[1]);
                System.out.println("  Extensions: "+alc.alcGetString(null, ALCConstants.ALC_EXTENSIONS));
                AudioSystem3D.checkError(device, "init."+checkErrIter++, DEBUG, false);
                System.out.println("ALAudioSink: Device "+device+" OpenALC:");
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_MAJOR_VERSION, 1, alcvers, 0);
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_MINOR_VERSION, 1, alcvers, 1);
                System.out.println("  Version: "+alcvers[0]+"."+alcvers[1]);
                System.out.println("  Extensions: "+alc.alcGetString(device.getALDevice(), ALCConstants.ALC_EXTENSIONS));
                System.out.println("ALAudioSink: hasSOFTBufferSamples "+hasSOFTBufferSamples);
                System.out.println("ALAudioSink: hasEXTMcFormats "+hasEXTMcFormats);
                System.out.println("ALAudioSink: hasEXTFloat32 "+hasEXTFloat32);
                System.out.println("ALAudioSink: hasEXTDouble "+hasEXTDouble);
                System.out.println("ALAudioSink: hasALC_thread_local_context "+hasALC_thread_local_context);
                System.out.println("ALAudioSink: hasAL_SOFT_events "+hasAL_SOFT_events);
                System.out.println("ALAudioSink: maxSupportedChannels "+getMaxSupportedChannels(false));
                System.out.println("ALAudioSink: nativeAudioFormat "+nativeFormat);
                System.out.println("ALAudioSink: defaultMixerRefreshRate "+(1000f*defaultLatency)+" ms, "+(1f/defaultLatency)+" Hz");
                AudioSystem3D.checkError(device, "init."+checkErrIter++, DEBUG, false);
            }

            if( DEBUG ) {
                logout.println("ALAudioSink: Using device: " + device);
            }
            available = true;
        } finally {
            release(true /* throw */);
        }
    }

    // Expose AudioSink OpenAL implementation specifics

    /** Return OpenAL global {@link AL}. */
    public static final AL getAL() { return al; }
    /** Return OpenAL global {@link ALC}. */
    public static final ALC getALC() { return alc; }
    /** Return OpenAL global {@link ALExt}. */
    public static final ALExt getALExt() { return alExt; }

    /** Return this instance's OpenAL {@link Device}. */
    public final Device getDevice() { return device; }
    /** Return this instance's OpenAL {@link Context}. */
    public final Context getContext() { return context; }
    /** Return this instance's OpenAL {@link Source}. */
    public final Source getSource() { return alSource; }

    /** Return whether OpenAL extension <code>AL_SOFT_buffer_samples</code> is available. */
    public final boolean hasSOFTBufferSamples() { return hasSOFTBufferSamples; }
    /** Return whether OpenAL extension <code>AL_EXT_MCFORMATS</code> is available. */
    public final boolean hasEXTMcFormats() { return hasEXTMcFormats; }
    /** Return whether OpenAL extension <code>AL_EXT_FLOAT32</code> is available. */
    public final boolean hasEXTFloat32() { return hasEXTFloat32; }
    /** Return whether OpenAL extension <code>AL_EXT_DOUBLE</code> is available. */
    public final boolean hasEXTDouble() { return hasEXTDouble; }
    /** Return whether OpenAL extension <code>ALC_EXT_thread_local_context</code> is available. */
    public final boolean hasALCThreadLocalContext() { return hasALC_thread_local_context; }
    /** Return whether OpenAL extension <code>AL_SOFT_events</code> is available. */
    public final boolean hasSOFTEvents() { return hasAL_SOFT_events; }
    /** Enable or disable <code>AL_SOFT_events</code>, default is enabled if {@link #hasSOFTEvents()}. */
    public final void setUseSOFTEvents(final boolean v) { useAL_SOFT_events = v; }
    /** Returns whether <code>AL_SOFT_events</code> is enabled, default if {@link #hasSOFTEvents()}. */
    public final boolean getUseSOFTEvents(final boolean v) { return useAL_SOFT_events; }

    /** Return this instance's OpenAL channel layout, set after {@link #init(AudioFormat, float, int)}. */
    public final int getALChannelLayout() { return alChannelLayout; }
    /** Return this instance's OpenAL sample type, set after {@link #init(AudioFormat, float, int)}. */
    public final int getALSampleType() { return alSampleType; }
    /** Return this instance's OpenAL format, set after {@link #init(AudioFormat, float, int)}. */
    public final int getALFormat() { return alFormat; }

    // AudioSink implementation ...

    @Override
    public final boolean makeCurrent(final boolean throwException) {
        return context.makeCurrent(throwException);
    }
    @Override
    public final boolean release(final boolean throwException) {
        return context.release(throwException);
    }
    private final void destroyContext() {
        context.destroy();
    }

    @Override
    public final String toString() {
        final int ctxHash = context != null ? context.hashCode() : 0;
        final int alFramesEnqueued = alFramesPlaying != null ? alFramesPlaying.size() : 0;
        final int alFramesFree_ = alFramesFree!= null ? alFramesFree.size() : 0;
        return String.format("ALAudioSink[playReq %b, device '%s', ctx 0x%x, alSource %d"+
               ", chosen %s, al[chan %s, type %s, fmt 0x%x, tlc %b, soft[buffer %b, events %b/%b]"+
               ", latency %.2f/%.2f ms, sources %d], playSpeed %.2f, "+
               "play[used %d, apts %d], queued[free %d, apts %d, %.1f ms, %d bytes, avg %.2f ms/frame, max %d ms]]",
               playRequested, device.getName(), ctxHash, alSource.getID(), chosenFormat,
               ALHelpers.alChannelLayoutName(alChannelLayout), ALHelpers.alSampleTypeName(alSampleType),
               alFormat, hasALC_thread_local_context, hasSOFTBufferSamples, useAL_SOFT_events, hasAL_SOFT_events,
               1000f*latency, 1000f*defaultLatency, sourceCount, playSpeed,
               alFramesEnqueued, getPTS().getLast(),
               alFramesFree_, getLastBufferedPTS(), 1000f*getQueuedDuration(), alBufferBytesQueued, 1000f*avgFrameDuration, queueSize
               );
    }

    public final String getPerfString() {
        final int alFramesEnqueued = alFramesPlaying != null ? alFramesPlaying.size() : 0;
        final int alFramesFree_ = alFramesFree!= null ? alFramesFree.size() : 0;
        return String.format("play[used %d, apts %d], queued[free %d, apts %d, %.1f ms, %d bytes, avg %.2f ms/frame, max %d ms]",
               alFramesEnqueued, getPTS().getLast(),
               alFramesFree_, getLastBufferedPTS(), 1000f*getQueuedDuration(), alBufferBytesQueued, 1000f*avgFrameDuration, queueSize
               );
    }

    @Override
    public int getSourceCount() { return sourceCount; }

    @Override
    public float getDefaultLatency() { return defaultLatency; }

    @Override
    public float getLatency() { return latency; }

    @Override
    public final AudioFormat getNativeFormat() {
        if( !staticsInitialized ) {
            return null;
        }
        return nativeFormat;
    }

    @Override
    public final AudioFormat getPreferredFormat() {
        if( !staticsInitialized ) {
            return null;
        }
        return preferredFormat;
    }

    @Override
    public final void setChannelLimit(final int cc) {
        userMaxChannels = Math.min(8, Math.max(1, cc));

        preferredFormat = new AudioFormat(nativeFormat.sampleRate,
                                       nativeFormat.sampleSize, getMaxSupportedChannels(true),
                                       nativeFormat.signed, nativeFormat.fixedP,
                                       nativeFormat.planar, nativeFormat.littleEndian);
        if( DEBUG ) {
            System.out.println("ALAudioSink: channelLimit "+userMaxChannels+", preferredFormat "+preferredFormat);
        }
    }

    private final int getMaxSupportedChannels(final boolean considerLimit) {
        if( !staticsInitialized ) {
            return 0;
        }
        final int cc;
        if( hasEXTMcFormats || hasSOFTBufferSamples ) {
            cc = 8;
        } else {
            cc = 2;
        }
        return considerLimit ? Math.min(userMaxChannels, cc) : cc;
    }

    @Override
    public final boolean isSupported(final AudioFormat format) {
        if( !staticsInitialized ) {
            return false;
        }
        if( format.planar != preferredFormat.planar ||
            format.littleEndian != preferredFormat.littleEndian ||
            format.sampleRate > preferredFormat.sampleRate ||
            format.channelCount > preferredFormat.channelCount )
        {
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.isSupported: NO.0 "+format);
            }
            return false;
        }
        final int alFormat = ALHelpers.getALFormat(format, al, alExt,
                                                   hasSOFTBufferSamples, hasEXTMcFormats,
                                                   hasEXTFloat32, hasEXTDouble);
        if( ALConstants.AL_NONE != alFormat ) {
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.isSupported: OK "+format+", alFormat "+toHexString(alFormat));
            }
            return true;
        } else {
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.isSupported: NO.1 "+format);
            }
            return false;
        }
    }

    @Override
    public final boolean init(final AudioFormat requestedFormat, final int frameDurationHint, final int queueSize)
    {
        if( !staticsInitialized ) {
            return false;
        }
        final int alChannelLayout = ALHelpers.getDefaultALChannelLayout(requestedFormat.channelCount);
        final int alSampleType = ALHelpers.getALSampleType(requestedFormat.sampleSize, requestedFormat.signed, requestedFormat.fixedP);
        final int alFormat;
        if( ALConstants.AL_NONE != alChannelLayout && ALConstants.AL_NONE != alSampleType ) {
            alFormat = ALHelpers.getALFormat(alChannelLayout, alSampleType, al, alExt,
                                             hasSOFTBufferSamples, hasEXTMcFormats,
                                             hasEXTFloat32, hasEXTDouble);
        } else {
            alFormat = ALConstants.AL_NONE;
        }
        if( ALConstants.AL_NONE == alFormat ) {
            // not supported
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.init1: Not supported: "+requestedFormat+", "+toString());
            }
            return false;
        }
        return initImpl(requestedFormat, alChannelLayout, alSampleType, alFormat, frameDurationHint/1000f, queueSize);
    }

    /**
     * Initializes the sink using the given OpenAL audio parameter and streaming details.
     * @param alChannelLayout OpenAL channel layout
     * @param alSampleType OpenAL sample type
     * @param alFormat OpenAL format
     * @param sampleRate sample rate, e.g. 44100
     * @param sampleSize sample size in bits, e.g. 16
     * @param frameDurationHint average {@link AudioFrame} duration hint in milliseconds.
     *                      Assists to adjust latency of the backend, as currently used for JOAL's ALAudioSink.
     *                      A value below 30ms or {@link #DefaultFrameDuration} may increase the audio processing load.
     *                      Assumed as {@link #DefaultFrameDuration}, if <code>frameDuration < 1 ms</code>.
     * @param queueSize     queue size in milliseconds, see {@link #DefaultQueueSize}.
     *                        Uses `frameDurationHint` to determine initial {@link AudioFrame} queue size.
     * @return true if successful, otherwise false
     * @see #enqueueData(int, ByteBuffer, int)
     * @see #getAvgFrameDuration()
     * @see ALHelpers#getAudioFormat(int, int, int, int, int)
     * @see #init(AudioFormat, float, int)
     */
    public final boolean init(final int alChannelLayout, final int alSampleType, final int alFormat,
                              final int sampleRate, final int sampleSize, final int frameDurationHint, final int queueSize)
    {
        final AudioFormat requestedFormat = ALHelpers.getAudioFormat(alChannelLayout, alSampleType, alFormat, sampleRate, sampleSize);
        if( null == requestedFormat ) {
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.init2: Invalid AL channelLayout "+toHexString(alChannelLayout)+
                        ", sampleType "+toHexString(alSampleType)+", format "+toHexString(alFormat)+" or sample[rate "+sampleRate+", size "+sampleSize+"]; "+toString());
            }
            return false;
        }
        return initImpl(requestedFormat, alChannelLayout, alSampleType, alFormat, frameDurationHint/1000f, queueSize);
    }

    private final synchronized boolean initImpl(final AudioFormat requestedFormat,
                                                final int alChannelLayout, final int alSampleType, final int alFormat,
                                                float frameDurationHintS, final int queueSize) {
        this.alChannelLayout = alChannelLayout;
        this.alSampleType = alSampleType;
        this.alFormat = alFormat;

        /**
         * OpenAL is pretty relaxed on formats, so whall we.
         * Deduced requested AudioFormat is already a validated OpenAL compatible one.
         * Drop filtering ..
        if( !isSupported(requestedFormat) ) {
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink.init: Requested format "+requestedFormat+" not supported, preferred is "+preferredFormat+", "+this);
            }
            return false;
        }
         */

        // Flush all old buffers
        makeCurrent(true /* throw */);
        if( context.getLockCount() != 1 ) {
            release(false);
            throw new ALException("init() must be called w/o makeCurrent: lockCount "+context+", "+this);
        }
        boolean releaseContext = true;
        try {
            stopImpl(true);
            destroySource();
            destroyBuffers();

            frameDurationHintS = frameDurationHintS >= 1f/1000f ? frameDurationHintS : AudioSink.DefaultFrameDuration/1000f;
            // Re-Create audio context if default latency is not sufficient
            {
                final int defRefreshRate = Math.round( 1f / defaultLatency ); // s -> Hz
                final int expMixerRefreshRate = Math.round( 1f / frameDurationHintS ); // s -> Hz

                if( frameDurationHintS < defaultLatency ) {
                    if( DEBUG ) {
                        logout.println(getThreadName()+": ALAudioSink.init: Re-create context as latency exp "+
                                (1000f*frameDurationHintS)+" ms ("+expMixerRefreshRate+" Hz) < default "+(1000f*defaultLatency)+" ms ("+defRefreshRate+" Hz)");
                    }
                    if( !context.recreate( new int[] { ALCConstants.ALC_REFRESH, expMixerRefreshRate } ) ) {
                        logout.println(getThreadName()+": ALAudioSink: Error creating OpenAL context "+context);
                        return false;
                    }
                } else if( DEBUG ) {
                    logout.println(getThreadName()+": ALAudioSink.init: Keep context, latency exp "+
                            (1000f*frameDurationHintS)+" ms ("+expMixerRefreshRate+" Hz) >= default "+(1000f*defaultLatency)+" ms ("+defRefreshRate+" Hz)");
                }
            }
            // Get actual refresh rate
            {
                final int[] value = { 0 };
                alc.alcGetIntegerv(device.getALDevice(), ALCConstants.ALC_REFRESH, 1, value, 0);
                if( AudioSystem3D.checkError(device, "read ALC_FREQUENCY", DEBUG, false) || 0 == value[0] ) {
                    latency = defaultLatency;
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryRefreshRate: failed, claiming default "+(1000f*latency)+" ms");
                    }
                } else {
                    latency = 1f/value[0]; // Hz -> ms
                    if( DEBUG ) {
                        logout.println("ALAudioSink.queryRefreshRate: OK "+value[0]+" Hz = "+(1000f*latency)+" ms");
                    }
                }
            }
            if( !createSource() ) {
                destroyContext();
                releaseContext = false;
                return false;
            }

            // Allocate new buffers
            {
                final int frameCount = requestedFormat.getFrameCount(
                                                queueSize > 0 ? queueSize/1000f : AudioSink.DefaultQueueSize/1000f, frameDurationHintS);
                alBufferNames = new int[frameCount];
                al.alGenBuffers(frameCount, alBufferNames, 0);
                if( AudioSystem3D.checkALError("alGenBuffers", true, false) ) {
                    alBufferNames = null;
                    destroySource();
                    destroyContext();
                    releaseContext = false;
                    return false;
                }
                final ALAudioFrame[] alFrames = new ALAudioFrame[frameCount];
                for(int i=0; i<frameCount; i++) {
                    alFrames[i] = new ALAudioFrame(alBufferNames[i]);
                }
                alFramesFree = new LFRingbuffer<ALAudioFrame>(alFrames);
                alFramesPlaying = new LFRingbuffer<ALAudioFrame>(ALAudioFrame[].class, frameCount);
                this.queueSize = queueSize > 0 ? queueSize : AudioSink.DefaultQueueSize;
                if( DEBUG_TRACE ) {
                    alFramesFree.dump(System.err, "Avail-init");
                    alFramesPlaying.dump(System.err, "Playi-init");
                }
            }
            if( hasAL_SOFT_events && useAL_SOFT_events ) {
                alExt.alEventCallbackSOFT(alEventCallback, context.getALContext());
                alExt.alEventControlSOFT(1, new int[] { ALExtConstants.AL_EVENT_TYPE_BUFFER_COMPLETED_SOFT }, 0, true);
            }
        } finally {
            if( releaseContext ) {
                release(false /* throw */);
            }
        }
        chosenFormat = requestedFormat;
        avgFrameDuration = latency;
        if( DEBUG ) {
            logout.println(getThreadName()+": ALAudioSink.init: OK "+requestedFormat+", "+toString());
        }
        return true;
    }

    @Override
    public final AudioFormat getChosenFormat() {
        return chosenFormat;
    }

    private void destroyBuffers() {
        if( !staticsInitialized ) {
            return;
        }
        if( null != alBufferNames ) {
            try {
                al.alDeleteBuffers(alBufferNames.length, alBufferNames, 0);
            } catch (final Throwable t) {
                if( DEBUG ) {
                    logout.println("Caught "+t.getClass().getName()+": "+t.getMessage());
                    t.printStackTrace();
                }
            }
            alFramesFree.clear();
            alFramesFree = null;
            alFramesPlaying.clear();
            alFramesPlaying = null;
            alBufferBytesQueued = 0;
            // alFrames = null;
            alBufferNames = null;
        }
    }

    private void destroySource() {
        if( !alSource.isValid() ) {
            return;
        }
        alSource.delete();
    }
    private boolean createSource() {
        if( alSource.isValid() ) {
            return true;
        }
        return alSource.create();
    }

    @Override
    public final void destroy() {
        if( !available ) {
            return;
        }
        available = false;
        if( null != context ) {
            makeCurrent(true /* throw */);
            if( hasAL_SOFT_events ) {
                alExt.alEventControlSOFT(3, new int[] { ALExtConstants.AL_EVENT_TYPE_BUFFER_COMPLETED_SOFT,
                                                        ALExtConstants.AL_EVENT_TYPE_SOURCE_STATE_CHANGED_SOFT,
                                                        ALExtConstants.AL_EVENT_TYPE_DISCONNECTED_SOFT
                                                      }, 0, false);
                alExt.alEventCallbackSOFT(null, context.getALContext());
            }
        }
        try {
            stopImpl(true);
            destroySource();
            destroyBuffers();
        } finally {
            destroyContext();
        }
        device.close();
        chosenFormat = null;
    }

    @Override
    public final boolean isAvailable() {
        return available;
    }

    final ALEVENTPROCSOFT alEventCallback = new ALEVENTPROCSOFT() {
        @SuppressWarnings("unused")
        @Override
        public void callback(final int eventType, final int object, final int param,
                             final int length, final String message, final ALCcontext context) {
            if( false ) {
                final com.jogamp.openal.ALContextKey k = new com.jogamp.openal.ALContextKey(context);
                logout.println("ALAudioSink.Event: type "+toHexString(eventType)+", obj "+toHexString(object)+", param "+param+
                        ", msg[len "+length+", val '"+message+"'], userParam "+k);
            }
            if( ALExtConstants.AL_EVENT_TYPE_BUFFER_COMPLETED_SOFT == eventType &&
                alSource.getID() == object )
            {
                synchronized( eventReleasedBuffersLock ) {
                    if( false ) {
                        final com.jogamp.openal.ALContextKey k = new com.jogamp.openal.ALContextKey(context);
                        logout.println("ALAudioSink.Event: type "+toHexString(eventType)+", obj "+toHexString(object)+
                                ", eventReleasedBuffers +"+param+" -> "+(eventReleasedBuffers + param)+
                                ", msg[len "+length+", val '"+message+"'], userParam "+k);
                    }
                    eventReleasedBuffers += param;
                    eventReleasedBuffersLock.notifyAll();
                }
            }
        }
    };
    private final Object eventReleasedBuffersLock = new Object();
    private volatile int eventReleasedBuffers = 0;

    private final int waitForReleasedEvent(final long t0, final boolean wait, final int releaseBufferCountReq) {
        if( alBufferBytesQueued == 0 ) {
            return 0;
        }
        final int enqueuedBuffers = alFramesPlaying.size();
        int wait_cycles=0;
        int slept = 0;
        int releasedBuffers = 0;
        do {
            synchronized( eventReleasedBuffersLock ) {
                while( wait && alBufferBytesQueued > 0 && eventReleasedBuffers < releaseBufferCountReq ) {
                    wait_cycles++;
                    try {
                        eventReleasedBuffersLock.wait();
                    } catch (final InterruptedException e) { }
                }
                // AL_SOFT_events cumulated released buffers is 'sometimes wrong'
                // Workaround: Query released buffers after receiving event and use minimum. (FIXME)
                final int releasedBuffersByEvent = eventReleasedBuffers;
                final int releasedBuffersByQuery = alSource.getBuffersProcessed();
                releasedBuffers = Math.min(releasedBuffersByEvent, releasedBuffersByQuery);
                eventReleasedBuffers = 0;
                if( DEBUG ) {
                    slept += Clock.currentMillis() - t0;
                    if( wait || releasedBuffers > 0 ) {
                        final String warnInfo = releasedBuffers != releasedBuffersByEvent ? " ** Warning ** " : "";
                        logout.println("ALAudioSink.DeqEvent["+wait_cycles+"]: released "+releasedBuffers+warnInfo+
                                " [enqeueud "+enqueuedBuffers+", event "+
                                releasedBuffersByEvent+", query "+releasedBuffersByQuery+"], req "+releaseBufferCountReq+", slept "+
                                slept+" ms, free total "+alFramesFree.size());
                    }
                }
            }
        } while ( wait && alBufferBytesQueued > 0 && releasedBuffers < releaseBufferCountReq );
        return releasedBuffers;
    }
    private final int waitForReleasedPoll(final boolean wait, final int releaseBufferCountReq) {
        if( alBufferBytesQueued == 0 ) {
            return 0;
        }
        final long sleepLimes = Math.round( releaseBufferCountReq * 1000.0*avgFrameDuration );
        int wait_cycles=0;
        long slept = 0;
        boolean onceBusyDebug = true;
        int releasedBuffers = 0;
        do {
            releasedBuffers = alSource.getBuffersProcessed();
            if( wait && releasedBuffers < releaseBufferCountReq ) {
                wait_cycles++;
                // clip wait at [avgFrameDuration .. 300] ms
                final int sleep = Math.max(2, Math.min(300, Math.round( (releaseBufferCountReq-releasedBuffers) * 1000f*avgFrameDuration) ) ) - 1; // 1 ms off for busy-loop
                if( slept + sleep + 1 <= sleepLimes ) {
                    if( DEBUG ) {
                        logout.println("ALAudioSink: DeqPoll["+wait_cycles+"].1:"+
                                "released "+releasedBuffers+"/"+releaseBufferCountReq+", sleep "+sleep+"/"+slept+"/"+sleepLimes+
                                " ms, "+getPerfString()+", state "+ALHelpers.alSourceStateString(getSourceState(false)));
                    }
                    release(true /* throw */);
                    try {
                        Thread.sleep( sleep );
                        slept += sleep;
                    } catch (final InterruptedException e) {
                    } finally {
                        makeCurrent(true /* throw */);
                    }
                } else {
                    // Empirical best behavior w/ openal-soft (sort of needs min ~21ms to complete processing a buffer even if period < 20ms?)
                    if( DEBUG ) {
                        if( onceBusyDebug ) {
                            logout.println("ALAudioSink: DeqPoll["+wait_cycles+"].2:"+
                                    "released "+releasedBuffers+"/"+releaseBufferCountReq+", sleep "+sleep+"->1/"+slept+"/"+sleepLimes+
                                    " ms, "+getPerfString()+", state "+ALHelpers.alSourceStateString(getSourceState(false)));
                            onceBusyDebug = false;
                        }
                    }
                    release(true /* throw */);
                    try {
                        Thread.sleep( 1 );
                        slept += 1;
                    } catch (final InterruptedException e) {
                    } finally {
                        makeCurrent(true /* throw */);
                    }
                }
            }
        } while ( wait && alBufferBytesQueued > 0 && releasedBuffers < releaseBufferCountReq );
        return releasedBuffers;
    }
    /**
     * Dequeuing playing audio frames.
     * @param wait if true, waiting for completion of audio buffers
     * @param releaseBufferCountReq number of buffers to be released
     */
    private final int dequeueBuffer(final boolean wait, final int releaseBufferCountReq) {
        final long t0 = Clock.currentMillis();
        final int releasedBufferCount;
        if( hasAL_SOFT_events && useAL_SOFT_events ) {
            releasedBufferCount = waitForReleasedEvent(t0, wait, releaseBufferCountReq);
        } else {
            releasedBufferCount = waitForReleasedPoll(wait, releaseBufferCountReq);
        }
        final long t1 = Clock.currentMillis();
        if( releasedBufferCount > 0 ) {
            final int[] buffers = new int[releasedBufferCount];
            alSource.unqueueBuffers(buffers);

            for ( int i=0; i<releasedBufferCount; i++ ) {
                final ALAudioFrame releasedBuffer = alFramesPlaying.get();
                if( null == releasedBuffer ) {
                    throw new InternalError("Internal Error: "+this);
                } else {
                    if( releasedBuffer.alBuffer != buffers[i] ) {
                        alFramesFree.dump(System.err, "Avail-deq02-post");
                        alFramesPlaying.dump(System.err, "Playi-deq02-post");
                        throw new InternalError("Buffer name mismatch: dequeued: "+buffers[i]+", released "+releasedBuffer+", "+this);
                    }
                    alBufferBytesQueued -= releasedBuffer.getByteSize();
                    {
                        pts.set(t1, releasedBuffer.getPTS() /* + releasedBuffer.getDuration() */);
                        // playingPTS = releasedBuffer.getPTS();
                        // final float queuedDuration = chosenFormat.getBytesDuration(alBufferBytesQueued); // [s]
                        // playingPTS += (int)( queuedDuration * 1.0f * 1000f + 0.5f ); // released frame already gone, add (forward) 80% of queued buffer duration
                    }
                    if( !alFramesFree.put(releasedBuffer) ) {
                        throw new InternalError("Internal Error: "+this);
                    }
                    if(DEBUG_TRACE) {
                        logout.println("<< [al "+buffers[i]+", q "+releasedBuffer.alBuffer+"] <- "+getPerfString()+" @ "+getThreadName());
                    }
                }
            }
            if( DEBUG ) {
                logout.println("ALAudioSink.Dequeued: "+(t1-t0)+
                        "ms , released "+releasedBufferCount+"/"+releaseBufferCountReq+", "+getPerfString()+
                        ", state "+ALHelpers.alSourceStateString(getSourceState(false)));
            }
        }
        return releasedBufferCount;
    }

    private final void dequeueForceAll() {
        if(DEBUG_TRACE) {
            logout.println("<   _FLUSH_  <- "+getPerfString()+" @ "+getThreadName());
        }
        int processedBufferCount = 0;
        al.alSourcei(alSource.getID(), ALConstants.AL_BUFFER, 0); // explicit force zero buffer!
        if(DEBUG_TRACE) {
            processedBufferCount = alSource.getBuffersProcessed();
        }
        final int alErr = al.alGetError();
        while ( !alFramesPlaying.isEmpty() ) {
            final ALAudioFrame releasedBuffer = alFramesPlaying.get();
            if( null == releasedBuffer ) {
                throw new InternalError("Internal Error: "+this);
            }
            alBufferBytesQueued -= releasedBuffer.getByteSize();
            if( !alFramesFree.put(releasedBuffer) ) {
                throw new InternalError("Internal Error: "+this);
            }
        }
        alBufferBytesQueued = 0;
        last_buffered_pts = TimeFrameI.INVALID_PTS;
        pts.set(0, TimeFrameI.INVALID_PTS);
        if(DEBUG_TRACE) {
            logout.println("<<  _FLUSH_  [al "+processedBufferCount+", err "+toHexString(alErr)+"] <- "+getPerfString()+" @ "+getThreadName());
            ExceptionUtils.dumpStack(System.err);
        }
    }

    @Override
    public final PTS updateQueue() {
        if( !available || null == chosenFormat ) {
            return pts;
        }

        // OpenAL consumes buffers in the background
        // we first need to initialize the OpenAL buffers then
        // start continuous playback.
        makeCurrent(true /* throw */);
        try {
            dequeueBuffer( false /* wait */, 1 );
        } finally {
            release(true /* throw */);
        }
        return pts;
    }

    @Override
    public final AudioFrame enqueueData(final int pts, final ByteBuffer bytes, final int byteCount) {
        if( !available || null == chosenFormat ) {
            return null;
        }
        final ALAudioFrame alFrame;

        // OpenAL consumes buffers in the background
        // we first need to initialize the OpenAL buffers then
        // start continuous playback.
        makeCurrent(true /* throw */);
        try {
            final int sourceState0 = DEBUG ? getSourceState(false) : 0;
            final float neededDuration = chosenFormat.getBytesDuration(byteCount); // [s]
            int enqueuedBuffers = alFramesPlaying.size();
            final char avgUpdateC;

            // 1) Update avgFrameDuration ..
            if( enqueuedBuffers > 2 ) {
                final float queuedDuration = chosenFormat.getBytesDuration(alBufferBytesQueued); // [s]
                avgFrameDuration = queuedDuration / enqueuedBuffers;
                avgUpdateC = '*';
            } else {
                avgUpdateC = '_';
            }

            // 2) SOFT dequeue w/o wait
            if( alFramesFree.isEmpty() || enqueuedBuffers > 2 ) {
                if( DEBUG ) {
                    logout.printf("ALAudioSink.DequeuSoft"+avgUpdateC+": %.2f ms, queued %d, %s%n",
                            1000f*neededDuration, enqueuedBuffers, getPerfString());
                }
                dequeueBuffer( false /* wait */, 1 );
            }
            enqueuedBuffers = alFramesPlaying.size();

            // 3) HARD dequeue with wait
            if( alFramesFree.isEmpty() && isPlayingImpl() ) {
                // possible if grow failed or already exceeds it's limit - only possible if playing ..
                final int releaseBuffersHardReq = Math.max(1, enqueuedBuffers / 3 ); // [1 .. enqueuedBuffers / 3]
                if( DEBUG ) {
                    logout.printf("ALAudioSink.DequeuHard"+avgUpdateC+": %.2f ms, req %d, queued %d, %s%n",
                            1000f*neededDuration, releaseBuffersHardReq, enqueuedBuffers, getPerfString());
                }
                dequeueBuffer( true /* wait */, releaseBuffersHardReq );
            }

            // 4) Add new frame
            alFrame = alFramesFree.get();
            if( null == alFrame ) {
                alFramesFree.dump(System.err, "Avail");
                throw new InternalError("Internal Error: avail.get null "+alFramesFree+", "+this);
            }
            alFrame.setPTS(pts);
            alFrame.setDuration(Math.round(1000f*neededDuration));
            alFrame.setByteSize(byteCount);
            if( !alFramesPlaying.put( alFrame ) ) {
                throw new InternalError("Internal Error: "+this);
            }
            last_buffered_pts = pts;
            final int[] alBufferNames = new int[] { alFrame.alBuffer };
            if( hasSOFTBufferSamples ) {
                final int samplesPerChannel = chosenFormat.getBytesSampleCount(byteCount) / chosenFormat.channelCount;
                // final int samplesPerChannel = ALHelpers.bytesToSampleCount(byteCount, alChannelLayout, alSampleType);
                alExt.alBufferSamplesSOFT(alFrame.alBuffer, chosenFormat.sampleRate, alFormat,
                                          samplesPerChannel, alChannelLayout, alSampleType, bytes);
            } else {
                al.alBufferData(alFrame.alBuffer, alFormat, bytes, byteCount, chosenFormat.sampleRate);
            }

            alSource.queueBuffers(alBufferNames);
            alBufferBytesQueued += byteCount;
            enqueuedFrameCount++; // safe: only written-to while locked!

            if(DEBUG_TRACE) {
                logout.println(">> "+alFrame.alBuffer+" -> "+getPerfString()+" @ "+getThreadName());
            }
            if( DEBUG ) {
                final int sourceState1 = getSourceState(false);
                playImpl(); // continue playing, fixes issue where we ran out of enqueued data!
                final int sourceState2 = getSourceState(false);
                if( sourceState0 != sourceState1 || sourceState0 != sourceState2 || sourceState1 != sourceState2 ) {
                    logout.printf("ALAudioSink.Enqueued   : %.2f ms, %s, state* %s -> %s -> %s; %s bytes%n",
                            1000f*neededDuration, getPerfString(),
                            ALHelpers.alSourceStateString(sourceState0), ALHelpers.alSourceStateString(sourceState1), ALHelpers.alSourceStateString(sourceState2), byteCount);
                } else {
                    logout.printf("ALAudioSink.Enqueued   : %.2f ms, %s, state %s; %d bytes%n",
                            1000f*neededDuration, getPerfString(), ALHelpers.alSourceStateString(sourceState2), byteCount);
                }
            } else {
                playImpl(); // continue playing, fixes issue where we ran out of enqueued data!
            }
        } finally {
            release(true /* throw */);
        }
        return alFrame;
    }

    @Override
    public final boolean isPlaying() {
        if( !available || null == chosenFormat ) {
            return false;
        }
        if( playRequested ) {
            makeCurrent(true /* throw */);
            try {
                return isPlayingImpl();
            } finally {
                release(true /* throw */);
            }
        } else {
            return false;
        }
    }
    private final boolean isPlayingImpl() {
        if( playRequested ) {
            return ALConstants.AL_PLAYING == getSourceState(false);
        } else {
            return false;
        }
    }
    private final int getSourceState(final boolean ignoreError) {
        if( !alSource.isValid() ) {
            final String msg = getThreadName()+": getSourceState: invalid "+alSource;
            if( ignoreError ) {
                if( DEBUG ) {
                    logout.println(msg);
                }
                return ALConstants.AL_NONE;
            } else {
                throw new ALException(msg);
            }
        }
        final int[] val = { ALConstants.AL_NONE };
        al.alGetSourcei(alSource.getID(), ALConstants.AL_SOURCE_STATE, val, 0);
        if( AudioSystem3D.checkALError("alGetSourcei", true, false) ) {
            final String msg = getThreadName()+": Error while querying SOURCE_STATE. "+this;
            if( ignoreError ) {
                if( DEBUG ) {
                    logout.println(msg);
                }
                return ALConstants.AL_NONE;
            } else {
                throw new ALException(msg);
            }
        }
        return val[0];
    }

    @Override
    public final void play() {
        if( !available || null == chosenFormat ) {
            return;
        }
        playRequested = true;
        makeCurrent(true /* throw */);
        try {
            playImpl();
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink: play, state "+ALHelpers.alSourceStateString(getSourceState(false))+", "+this);
            }
        } finally {
            release(true /* throw */);
        }
    }
    private final void playImpl() {
        if( playRequested && ALConstants.AL_PLAYING != getSourceState(false) ) {
            alSource.play();
            AudioSystem3D.checkALError("alSourcePlay", true, true);
        }
    }

    @Override
    public final void pause() {
        if( !available || null == chosenFormat ) {
            return;
        }
        if( playRequested ) {
            makeCurrent(true /* throw */);
            try {
                pauseImpl();
                if( DEBUG ) {
                    logout.println(getThreadName()+": ALAudioSink: pause, state "+ALHelpers.alSourceStateString(getSourceState(false))+", "+this);
                }
            } finally {
                release(true /* throw */);
            }
        }
    }
    private final void pauseImpl() {
        if( isPlayingImpl() ) {
            playRequested = false;
            alSource.pause();
            AudioSystem3D.checkALError("alSourcePause", true, true);
        }
    }
    private final void stopImpl(final boolean ignoreError) {
        if( !alSource.isValid() ) {
            return;
        }
        if( ALConstants.AL_STOPPED != getSourceState(ignoreError) ) {
            playRequested = false;
            alSource.stop();
            if( AudioSystem3D.checkALError("alSourcePause", true, false) ) {
                final String msg = "Error while stopping. "+this;
                if( ignoreError ) {
                    if( DEBUG ) {
                        logout.println(getThreadName()+": "+msg);
                    }
                } else {
                    throw new ALException(getThreadName()+": Error while stopping. "+this);
                }
            }
        }
    }

    @Override
    public final float getPlaySpeed() { return playSpeed; }

    @Override
    public final boolean setPlaySpeed(float rate) {
        if( !available || null == chosenFormat ) {
            return false;
        }
        makeCurrent(true /* throw */);
        try {
            if( Math.abs(1.0f - rate) < 0.01f ) {
                rate = 1.0f;
            }
            if( 0.5f <= rate && rate <= 2.0f ) { // OpenAL limits
                playSpeed = rate;
                alSource.setPitch(playSpeed);
                return true;
            }
        } finally {
            release(true /* throw */);
        }
        return false;
    }

    @Override
    public final float getVolume() {
        return volume;
    }

    private static final float clipAudioVolume(final float v) {
        if( v < 0.01f ) {
            return 0.0f;
        } else if( Math.abs(1.0f - v) < 0.01f ) {
            return 1.0f;
        }
        return v;
    }
    @Override
    public final boolean setVolume(float v) {
        if( !available || null == chosenFormat ) {
            return false;
        }
        makeCurrent(true /* throw */);
        try {
            v = clipAudioVolume(v);
            if( 0.0f <= v && v <= 1.0f ) { // OpenAL limits
                volume = v;
                alSource.setGain(v);
                return true;
            }
        } finally {
            release(true /* throw */);
        }
        return false;
    }

    @Override
    public final void flush() {
        if( !available || null == chosenFormat ) {
            return;
        }
        makeCurrent(true /* throw */);
        try {
            // pauseImpl();
            stopImpl(false);
            // Redundant: dequeueBuffer( false /* wait */, true /* ignoreBufferInconsistency */);
            dequeueForceAll();
            if( alBufferNames.length != alFramesFree.size() || alFramesPlaying.size() != 0 ) {
                throw new InternalError("XXX: "+this);
            }
            if( DEBUG ) {
                logout.println(getThreadName()+": ALAudioSink: flush, state "+ALHelpers.alSourceStateString(getSourceState(false))+", "+this);
            }
        } finally {
            release(true /* throw */);
        }
    }

    @Override
    public final int getEnqueuedFrameCount() {
        return enqueuedFrameCount;
    }

    @Override
    public final int getFrameCount() {
        return null != alBufferNames ? alBufferNames.length : 0;
    }

    @Override
    public final int getQueuedFrameCount() {
        if( !available || null == chosenFormat ) {
            return 0;
        }
        return alFramesPlaying.size();
    }

    @Override
    public final int getFreeFrameCount() {
        if( !available || null == chosenFormat ) {
            return 0;
        }
        return alFramesFree.size();
    }

    @Override
    public final int getQueuedByteCount() {
        if( !available || null == chosenFormat ) {
            return 0;
        }
        return alBufferBytesQueued;
    }

    @Override
    public final float getQueuedDuration() {
        if( !available || null == chosenFormat ) {
            return 0;
        }
        return chosenFormat.getBytesDuration(alBufferBytesQueued);
    }

    @Override
    public float getAvgFrameDuration() {
        return avgFrameDuration;
    }

    @Override
    public final PTS getPTS() { return pts; }

    @Override
    public final int getLastBufferedPTS() { return last_buffered_pts; }

    private static final String toHexString(final int v) { return "0x"+Integer.toHexString(v); }
    private static final String getThreadName() { return Thread.currentThread().getName(); }
}

// vstrender - headless VST3 offline renderer (Windows x64)
// Usage: vstrender -plugin <bundle.vst3> -preset <file.vstpreset> -in <in.wav> -out <out.wav> [-block 512]
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>

#include "public.sdk/source/vst/hosting/module.h"
#include "public.sdk/source/vst/hosting/hostclasses.h"
#include "public.sdk/source/vst/hosting/plugprovider.h"
#include "public.sdk/source/vst/hosting/processdata.h"
#include "public.sdk/source/vst/hosting/parameterchanges.h"
#include "public.sdk/source/vst/hosting/eventlist.h"
#include "public.sdk/source/common/memorystream.h"
#include "pluginterfaces/base/ibstream.h"
#include "public.sdk/source/vst/utility/stringconvert.h"
#include "pluginterfaces/vst/vstspeaker.h"
#include "pluginterfaces/vst/ivstprocesscontext.h"
#include "pluginterfaces/gui/iplugview.h"
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include "pluginterfaces/vst/ivstaudioprocessor.h"
#include "pluginterfaces/vst/ivstcomponent.h"
#include "pluginterfaces/vst/ivsteditcontroller.h"
#include "pluginterfaces/vst/ivsthostapplication.h"
#include "pluginterfaces/base/funknownimpl.h"

using namespace Steinberg;
using namespace Steinberg::Vst;
namespace Host = VST3::Hosting;

class CH : public IComponentHandler
{
public:
	tresult PLUGIN_API beginEdit (ParamID) SMTG_OVERRIDE { return kResultOk; }
	tresult PLUGIN_API performEdit (ParamID, ParamValue) SMTG_OVERRIDE { return kResultOk; }
	tresult PLUGIN_API endEdit (ParamID) SMTG_OVERRIDE { return kResultOk; }
	tresult PLUGIN_API restartComponent (int32) SMTG_OVERRIDE { return kResultOk; }
	DECLARE_FUNKNOWN_METHODS
};
IMPLEMENT_FUNKNOWN_METHODS (CH, IComponentHandler, IComponentHandler::iid)

class PlugFrame : public IPlugFrame
{
public:
	tresult PLUGIN_API resizeView (IPlugView* view, ViewRect* newSize) SMTG_OVERRIDE
	{
		if (view && newSize) return view->onSize (newSize);
		return kResultOk;
	}
	DECLARE_FUNKNOWN_METHODS
};
IMPLEMENT_FUNKNOWN_METHODS (PlugFrame, IPlugFrame, IPlugFrame::iid)

static void logf (const char* fmt, ...)
{
	va_list ap;
	va_start (ap, fmt);
	vfprintf (stderr, fmt, ap);
	va_end (ap);
	fputc ('\n', stderr);
}

//------------------------------------------------------------------------
struct WavData
{
	uint32_t sampleRate {0};
	uint16_t channels {0};
	std::vector<float> samples; // interleaved
};

static uint16_t rd16 (const unsigned char* p) { return (uint16_t) (p[0] | (p[1] << 8)); }
static uint32_t rd32 (const unsigned char* p)
{
	return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}
static void wr16 (std::ostream& os, uint16_t v) { char b[2] = {(char) (v & 0xff), (char) ((v >> 8) & 0xff)}; os.write (b, 2); }
static void wr32 (std::ostream& os, uint32_t v)
{
	char b[4] = {(char) (v & 0xff), (char) ((v >> 8) & 0xff), (char) ((v >> 16) & 0xff), (char) ((v >> 24) & 0xff)};
	os.write (b, 4);
}

static bool readWav (const std::string& path, WavData& out, std::string& err)
{
	std::ifstream f (path, std::ios::binary);
	if (!f) { err = "cannot open input wav"; return false; }
	std::vector<unsigned char> d ((std::istreambuf_iterator<char> (f)), std::istreambuf_iterator<char> ());
	if (d.size () < 44 || memcmp (d.data (), "RIFF", 4) != 0 || memcmp (d.data () + 8, "WAVE", 4) != 0)
	{ err = "not a RIFF/WAVE file"; return false; }
	uint16_t fmt = 0, ch = 0, bits = 0;
	uint32_t sr = 0;
	const unsigned char* dataPtr = nullptr;
	uint32_t dataLen = 0;
	size_t pos = 12;
	while (pos + 8 <= d.size ())
	{
		uint32_t csize = rd32 (d.data () + pos + 4);
		if (memcmp (d.data () + pos, "fmt ", 4) == 0 && pos + 8 + 16 <= d.size ())
		{
			fmt = rd16 (d.data () + pos + 8);
			ch = rd16 (d.data () + pos + 10);
			sr = rd32 (d.data () + pos + 12);
			bits = rd16 (d.data () + pos + 22);
			if (fmt == 0xFFFE && csize >= 40 && pos + 8 + 26 < d.size ())
				fmt = rd16 (d.data () + pos + 8 + 24);
		}
		else if (memcmp (d.data () + pos, "data", 4) == 0)
		{
			dataPtr = d.data () + pos + 8;
			dataLen = std::min<uint32_t> (csize, (uint32_t) (d.size () - pos - 8));
		}
		pos += 8 + csize + (csize & 1);
	}
	if (!dataPtr || !ch || !sr) { err = "wav missing fmt/data chunk"; return false; }
	out.sampleRate = sr;
	out.channels = ch;
	size_t frames = dataLen / (bits / 8) / ch;
	out.samples.resize (frames * ch);
	const unsigned char* p = dataPtr;
	for (size_t i = 0; i < frames * ch; ++i)
	{
		float v = 0.f;
		if (fmt == 3 && bits == 32) { float t; memcpy (&t, p, 4); v = t; p += 4; }
		else if (fmt == 1 && bits == 16) { int16_t t; memcpy (&t, p, 2); v = t / 32768.f; p += 2; }
		else if (fmt == 1 && bits == 24)
		{
			int32_t t = (int32_t) p[0] | ((int32_t) p[1] << 8) | ((int32_t) p[2] << 16);
			if (t & 0x800000) t |= (int32_t) 0xFF000000;
			v = t / 8388608.f; p += 3;
		}
		else if (fmt == 1 && bits == 32) { int32_t t; memcpy (&t, p, 4); v = t / 2147483648.f; p += 4; }
		else { err = "unsupported wav format"; return false; }
		out.samples[i] = v;
	}
	return true;
}

// streaming 24-bit PCM writer
struct WavWriter
{
	std::ofstream f;
	uint32_t channels {0}, sampleRate {0};
	uint64_t frames {0};
	bool open (const std::string& path, uint32_t ch, uint32_t sr, std::string& err)
	{
		f.open (path, std::ios::binary | std::ios::trunc);
		if (!f) { err = "cannot create output wav"; return false; }
		channels = ch; sampleRate = sr; frames = 0;
		f.write ("RIFF", 4); wr32 (f, 0); f.write ("WAVE", 4);
		f.write ("fmt ", 4); wr32 (f, 16); wr16 (f, 1); wr16 (f, (uint16_t) ch); wr32 (f, sr);
		wr32 (f, sr * ch * 3); wr16 (f, (uint16_t) (ch * 3)); wr16 (f, 24);
		f.write ("data", 4); wr32 (f, 0);
		return (bool) f;
	}
	void writeFrames (const float* interleaved, size_t count)
	{
		size_t n = count * channels;
		std::vector<unsigned char> buf (n * 3);
		for (size_t i = 0; i < n; ++i)
		{
			float v = interleaved[i];
			v = std::max (-1.f, std::min (1.f, v));
			int32_t s = (int32_t) lrintf (v * 8388607.f);
			buf[i * 3] = (unsigned char) (s & 0xff);
			buf[i * 3 + 1] = (unsigned char) ((s >> 8) & 0xff);
			buf[i * 3 + 2] = (unsigned char) ((s >> 16) & 0xff);
		}
		f.write ((const char*) buf.data (), (std::streamsize) buf.size ());
		frames += count;
	}
	void close ()
	{
		uint32_t dataBytes = (uint32_t) (frames * channels * 3);
		f.seekp (4); wr32 (f, 36 + dataBytes);
		f.seekp (40); wr32 (f, dataBytes);
		f.close ();
	}
};

//------------------------------------------------------------------------
struct PresetChunks
{
	std::vector<char> comp;
	std::vector<char> cont;
};

static bool readPreset (const std::string& path, PresetChunks& out, std::string& err)
{
	std::ifstream f (path, std::ios::binary);
	if (!f) { err = "cannot open preset"; return false; }
	std::vector<char> d ((std::istreambuf_iterator<char> (f)), std::istreambuf_iterator<char> ());
	if (d.size () < 84 || memcmp (d.data (), "VST3", 4) != 0) { err = "not a vstpreset"; return false; }
	size_t n = d.size ();
	for (int i = 0; i < 3; ++i)
	{
		size_t o = n - 60 + i * 20;
		if (o + 20 > n) break;
		char id[5] = {d[o], d[o + 1], d[o + 2], d[o + 3], 0};
		int64_t off = 0, sz = 0;
		memcpy (&off, d.data () + o + 4, 8);
		memcpy (&sz, d.data () + o + 12, 8);
		if (off < 0 || sz < 0 || (uint64_t) (off + sz) > n) continue;
		if (strcmp (id, "Comp") == 0) out.comp.assign (d.begin () + off, d.begin () + off + sz);
		else if (strcmp (id, "Cont") == 0) out.cont.assign (d.begin () + off, d.begin () + off + sz);
	}
	return true;
}

//------------------------------------------------------------------------
int main (int argc, char** argv)
{
	std::string pluginPath, presetPath, inPath, outPath;
	int blockSize = 512;
	int outChannels = 0;
	bool listParams = false;
	bool offlineMode = false;
	for (int i = 1; i < argc; ++i)
	{
		std::string a = argv[i];
		auto next = [&] () -> std::string { return (i + 1 < argc) ? argv[++i] : std::string (); };
		if (a == "-plugin") pluginPath = next ();
		else if (a == "-preset") presetPath = next ();
		else if (a == "-in") inPath = next ();
		else if (a == "-out") outPath = next ();
		else if (a == "-block") blockSize = std::max (32, std::min (4096, atoi (next ().c_str ())));
		else if (a == "-list") listParams = true;
		else if (a == "-outch") outChannels = atoi (next ().c_str ());
		else if (a == "-offline") offlineMode = true;
		else if (a == "-h" || a == "--help")
		{
			fprintf (stdout, "usage: vstrender -plugin <bundle.vst3> -preset <file.vstpreset> -in <in.wav> -out <out.wav> [-block 512]\n");
			return 0;
		}
	}
	if (pluginPath.empty () || (!listParams && (inPath.empty () || outPath.empty ())))
	{
		logf ("missing arguments (need -plugin -in -out)");
		return 2;
	}

	std::string err;
	auto module = Host::Module::create (pluginPath, err);
	if (!module) { logf ("load module failed: %s", err.c_str ()); return 4; }
	const auto& factory = module->getFactory ();
	Host::ClassInfo classInfo;
	bool foundClass = false;
	for (const auto& ci : factory.classInfos ())
		if (ci.category () == "Audio Module Class") { classInfo = ci; foundClass = true; break; }
	if (!foundClass)
		for (const auto& ci : factory.classInfos ()) { classInfo = ci; foundClass = true; break; }
	if (!foundClass) { logf ("no plugin class found"); return 5; }
	CH componentHandler;
	logf ("plugin class: %s / %s", classInfo.name ().c_str (), classInfo.category ().c_str ());

	HostApplication hostApp;
	PluginContextFactory::instance ().setPluginContext (&hostApp);
	PlugProvider provider (factory, classInfo, true);
	if (!provider.initialize ()) { logf ("plugin initialize failed"); return 6; }
	auto comp = provider.getComponentPtr ();
	auto ctrl = provider.getControllerPtr ();
	if (!comp) { logf ("no component"); return 7; }
	if (ctrl) ctrl->setComponentHandler (&componentHandler);
	IAudioProcessor* procRaw = nullptr;
	if (comp->queryInterface (IAudioProcessor::iid, (void**) &procRaw) != kResultOk || !procRaw)
	{
		logf ("plugin does not implement IAudioProcessor");
		return 7;
	}
	IPtr<IAudioProcessor> proc = owned (procRaw);
	if (listParams)
	{
		auto c = provider.getControllerPtr ();
		if (!c) { logf ("no controller"); return 0; }
		for (int32 i = 0; i < c->getParameterCount (); ++i)
		{
			ParameterInfo info {};
			if (c->getParameterInfo (i, info) == kResultOk)
			{
				auto title = StringConvert::convert (info.title);
				auto units = StringConvert::convert (info.units);
				logf ("%4d id=%-8u %-42s %-10s def=%.6f steps=%d", i, (unsigned) info.id, title.c_str (), units.c_str (), info.defaultNormalizedValue, info.stepCount);
			}
		}
		return 0;
	}

	WavData in;
	if (!readWav (inPath, in, err)) { logf ("read wav: %s", err.c_str ()); return 3; }
	logf ("input: %u Hz, %u ch, %zu frames", in.sampleRate, in.channels, in.samples.size () / std::max<uint16_t> (1, in.channels));

	if (!presetPath.empty ())
	{
		PresetChunks pc;
		if (!readPreset (presetPath, pc, err)) { logf ("preset: %s", err.c_str ()); return 8; }
		if (!pc.comp.empty ())
		{
			MemoryStream ms (pc.comp.data (), pc.comp.size ());
			if (comp->setState (&ms) != kResultOk) logf ("warning: component setState failed");
			else logf ("component state loaded (%zu bytes)", pc.comp.size ());
			if (ctrl)
			{
				ms.seek (0, IBStream::kIBSeekSet, nullptr);
				if (ctrl->setComponentState (&ms) != kResultOk) logf ("warning: controller setComponentState failed");
			}
		}
		if (ctrl && !pc.cont.empty ())
		{
			MemoryStream ms (pc.cont.data (), pc.cont.size ());
			if (ctrl->setState (&ms) != kResultOk) logf ("warning: controller setState failed");
		}
	}

	if (outChannels == 2)
	{
		SpeakerArrangement inArr = SpeakerArr::kStereo;
		SpeakerArrangement outArr = SpeakerArr::kStereo;
		if (proc->setBusArrangements (&inArr, 1, &outArr, 1) != kResultOk)
			logf ("warning: stereo bus arrangement rejected, using default");
		else
			logf ("bus arrangement set to stereo");
	}

	ProcessSetup setup {};
	setup.processMode = offlineMode ? kOffline : kRealtime;
	setup.symbolicSampleSize = kSample32;
	setup.maxSamplesPerBlock = blockSize;
	setup.sampleRate = (double) in.sampleRate;
	{
		MemoryStream defState;
		if (comp->getState (&defState) == kResultOk && defState.getSize () > 0)
		{
			defState.seek (0, IBStream::kIBSeekSet, nullptr);
			if (comp->setState (&defState) == kResultOk) logf ("default state round-trip ok (%zu bytes)", (size_t) defState.getSize ());
			if (ctrl)
			{
				defState.seek (0, IBStream::kIBSeekSet, nullptr);
				if (ctrl->setComponentState (&defState) == kResultOk) logf ("controller setComponentState ok");
			}
		}
	}
	comp->setActive (false);
	proc->setProcessing (false);
	if (proc->setupProcessing (setup) != kResultOk) { logf ("setupProcessing failed (sample rate %u unsupported?)", in.sampleRate); return 9; }

	HostProcessData pd;
	if (!pd.prepare (*comp, 0, kSample32)) { logf ("process data prepare failed"); return 11; }
	auto allocBuffers = [&] (AudioBusBuffers* buses, int count) {
		if (!buses) return;
		for (int b = 0; b < count; ++b)
			for (int ch = 0; ch < buses[b].numChannels; ++ch)
				if (buses[b].channelBuffers32 && buses[b].channelBuffers32[ch] == nullptr)
					buses[b].channelBuffers32[ch] = new float[blockSize] ();
	};
	allocBuffers (pd.inputs, pd.numInputs);
	allocBuffers (pd.outputs, pd.numOutputs);
	EventList inputEvents;
	ParameterChanges inputParamChanges;
	pd.inputEvents = &inputEvents;
	pd.inputParameterChanges = &inputParamChanges;
	ParameterChanges outputParamChanges;
	pd.outputParameterChanges = &outputParamChanges;

	IPlugView* view = nullptr;
	PlugFrame plugFrame;
	HWND hiddenWnd = nullptr;
	if (ctrl)
	{
		view = ctrl->createView (ViewType::kEditor);
		if (view)
		{
			view->setFrame (&plugFrame);
			hiddenWnd = CreateWindowExA (0, "STATIC", "vsthidden", WS_POPUP, 0, 0, 200, 200, nullptr, nullptr, GetModuleHandleA (nullptr), nullptr);
			if (hiddenWnd && view->isPlatformTypeSupported (kPlatformTypeHWND) == kResultTrue)
			{
				if (view->attached (hiddenWnd, kPlatformTypeHWND) == kResultOk)
					logf ("editor view attached to hidden window");
				else
					logf ("warning: view attach failed");
			}
			else
				logf ("warning: HWND platform not supported");
		}
		else
			logf ("warning: createView returned null");
	}

	if (comp->setActive (true) != kResultOk) { logf ("setActive failed"); return 10; }
	if (proc->setProcessing (true) != kResultOk) logf ("warning: setProcessing failed");
	Sleep (1500);
	{ MSG m; while (PeekMessageA (&m, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage (&m); DispatchMessageA (&m); } }
	int inCh = pd.numInputs > 0 ? pd.inputs[0].numChannels : 0;
	int outCh = pd.numOutputs > 0 ? pd.outputs[0].numChannels : 0;
	logf ("buses: %d in (%d ch), %d out (%d ch)", pd.numInputs, inCh, pd.numOutputs, outCh);
	for (int dir = 0; dir < 2; ++dir)
	{
		BusDirections d = dir == 0 ? kInput : kOutput;
		int cnt = comp->getBusCount (kAudio, d);
		for (int i = 0; i < cnt; ++i)
		{
			BusInfo bi {};
			if (comp->getBusInfo (kAudio, d, i, bi) == kResultOk)
			{
				auto nm = StringConvert::convert (bi.name);
				logf ("bus %s%d: name='%s' ch=%d busType=%d flags=0x%x", dir == 0 ? "in" : "out", i, nm.c_str (), bi.channelCount, bi.busType, bi.flags);
			}
		}
	}
	if (outCh <= 0) { logf ("no output bus"); return 12; }

	WavWriter writer;
	if (!writer.open (outPath, (uint32_t) outCh, in.sampleRate, err)) { logf ("%s", err.c_str ()); return 13; }

	size_t totalFrames = in.samples.size () / std::max<uint16_t> (1, in.channels);
	std::vector<float> outBlock ((size_t) blockSize * outCh);
	ProcessContext pctx {};
	pctx.state = ProcessContext::kPlaying | ProcessContext::kContTimeValid | ProcessContext::kSystemTimeValid |
	             ProcessContext::kProjectTimeMusicValid | ProcessContext::kTempoValid | ProcessContext::kTimeSigValid;
	pctx.sampleRate = (double) in.sampleRate;
	pctx.tempo = 120.0;
	pctx.timeSigNumerator = 4;
	pctx.timeSigDenominator = 4;
	pd.processContext = &pctx;
	size_t done = 0;
	while (done < totalFrames)
	{
		int n = (int) std::min<size_t> ((size_t) blockSize, totalFrames - done);
		for (int b = 0; b < pd.numInputs; ++b)
			for (int ch = 0; ch < pd.inputs[b].numChannels; ++ch)
				if (pd.inputs[b].channelBuffers32 && pd.inputs[b].channelBuffers32[ch])
					memset (pd.inputs[b].channelBuffers32[ch], 0, sizeof (float) * blockSize);
		if (pd.numInputs > 0)
		{
			for (int ch = 0; ch < inCh; ++ch)
			{
				float* dst = pd.inputs[0].channelBuffers32[ch];
				int srcCh = std::min<int> (ch, in.channels - 1);
				for (int i = 0; i < n; ++i)
					dst[i] = in.samples[(done + i) * in.channels + srcCh];
			}
		}
		for (int b = 0; b < pd.numOutputs; ++b)
			for (int ch = 0; ch < pd.outputs[b].numChannels; ++ch)
				if (pd.outputs[b].channelBuffers32 && pd.outputs[b].channelBuffers32[ch])
					std::fill (pd.outputs[b].channelBuffers32[ch], pd.outputs[b].channelBuffers32[ch] + blockSize, 0.5f);
		pd.processMode = offlineMode ? kOffline : kRealtime;
		pd.symbolicSampleSize = kSample32;
		for (int b = 0; b < pd.numInputs; ++b) pd.inputs[b].silenceFlags = 0;
		for (int b = 0; b < pd.numOutputs; ++b) pd.outputs[b].silenceFlags = 0;
		pd.numSamples = n;
		pctx.projectTimeSamples = (TSamples) done;
		pctx.continousTimeSamples = (TSamples) done;
		pctx.projectTimeMusic = (double) done / in.sampleRate;
		pctx.systemTime = (TSamples) ((double) done / in.sampleRate * 1e9);
		if (proc->process (pd) != kResultOk) { logf ("process failed at frame %zu", done); return 14; }
		for (int i = 0; i < n; ++i)
			for (int ch = 0; ch < outCh; ++ch)
				outBlock[(size_t) i * outCh + ch] = pd.outputs[0].channelBuffers32[ch][i];
		if (done == 0)
		{
			MSG msg;
			while (PeekMessageA (&msg, nullptr, 0, 0, PM_REMOVE)) { TranslateMessage (&msg); DispatchMessageA (&msg); }
			logf ("[debug] out ptr=%llx in ptr=%llx", (unsigned long long) (uintptr_t) pd.outputs[0].channelBuffers32[0],
			      (unsigned long long) (uintptr_t) pd.inputs[0].channelBuffers32[0]);
			logf ("[debug] silenceFlags in=0x%llx out=0x%llx, outCh=%d", (unsigned long long) pd.inputs[0].silenceFlags,
			      (unsigned long long) pd.outputs[0].silenceFlags, pd.outputs[0].numChannels);
			logf ("[debug] first block out ch0[0..3]=%.6f %.6f %.6f %.6f ch1[0..3]=%.6f %.6f %.6f %.6f",
			      pd.outputs[0].channelBuffers32[0][0], pd.outputs[0].channelBuffers32[0][1],
			      pd.outputs[0].channelBuffers32[0][2], pd.outputs[0].channelBuffers32[0][3],
			      pd.outputs[0].numChannels > 1 ? pd.outputs[0].channelBuffers32[1][0] : 0.f,
			      pd.outputs[0].numChannels > 1 ? pd.outputs[0].channelBuffers32[1][1] : 0.f,
			      pd.outputs[0].numChannels > 1 ? pd.outputs[0].channelBuffers32[1][2] : 0.f,
			      pd.outputs[0].numChannels > 1 ? pd.outputs[0].channelBuffers32[1][3] : 0.f);
			logf ("[debug] first block in ch0[0..3]=%.6f %.6f %.6f %.6f", pd.inputs[0].channelBuffers32[0][0],
			      pd.inputs[0].channelBuffers32[0][1], pd.inputs[0].channelBuffers32[0][2],
			      pd.inputs[0].channelBuffers32[0][3]);
		}
		writer.writeFrames (outBlock.data (), (size_t) n);
		done += n;
	}
	writer.close ();

	proc->setProcessing (false);
	comp->setActive (false);
	if (view) { view->setFrame (nullptr); if (hiddenWnd) { view->removed (); DestroyWindow (hiddenWnd); } view->release (); }
	logf ("done: %zu frames, %d ch, %s", done, outCh, outPath.c_str ());
	return 0;
}

// vstrender_juce - JUCE-based headless VST3 renderer
// modes: single/batch render, preset save, resident serve (stdin command loop)
#include <juce_audio_processors/juce_audio_processors.h>
#include <juce_audio_formats/juce_audio_formats.h>
#include <juce_audio_processors/format/juce_AudioPluginFormatManagerHelpers.h>
#include <iostream>
#include <cstring>
#include <string>
#include <vector>
#include <functional>

using namespace juce;

struct PresetChunks { std::vector<char> comp, cont; };

static bool readPreset (const String& path, PresetChunks& out)
{
    File f (path);
    if (!f.existsAsFile()) return false;
    FileInputStream in (f);
    if (!in.openedOk()) return false;
    auto n = in.getTotalLength();
    if (n < 84) return false;
    std::vector<char> d ((size_t) n);
    in.read (d.data(), (int) n);
    if (memcmp (d.data(), "VST3", 4) != 0) return false;
    for (int i = 0; i < 3; ++i)
    {
        size_t o = (size_t) n - 60 + i * 20;
        if (o + 20 > (size_t) n) break;
        char id[5] = { d[o], d[o+1], d[o+2], d[o+3], 0 };
        int64_t off = 0, sz = 0;
        memcpy (&off, d.data() + o + 4, 8);
        memcpy (&sz, d.data() + o + 12, 8);
        if (off < 0 || sz < 0 || (uint64_t)(off + sz) > (uint64_t) n) continue;
        if (strcmp (id, "Comp") == 0) out.comp.assign (d.begin() + off, d.begin() + off + sz);
        else if (strcmp (id, "Cont") == 0) out.cont.assign (d.begin() + off, d.begin() + off + sz);
    }
    return true;
}

static void applyPreset (AudioPluginInstance& inst, const PresetChunks& pc)
{
    if (pc.comp.empty()) return;
    juce::XmlElement head ("VST3PluginState");
    {
        juce::MemoryBlock mb (pc.comp.data(), pc.comp.size());
        head.createNewChildElement ("IComponent")->addTextElement (mb.toBase64Encoding());
    }
    if (!pc.cont.empty())
    {
        juce::MemoryBlock mc (pc.cont.data(), pc.cont.size());
        head.createNewChildElement ("IEditController")->addTextElement (mc.toBase64Encoding());
    }
    juce::MemoryBlock bin;
    juce::AudioProcessor::copyXmlToBinary (head, bin);
    inst.setStateInformation (bin.getData(), (int) bin.getSize());
}

// Creates a hosted VST3 instance with exactly the same setup the startup path uses:
// stereo output bus, given sample rate and block size. Returns nullptr on failure.
static std::unique_ptr<AudioPluginInstance> createHostedInstance (VST3PluginFormat& format,
                                                                 const PluginDescription& desc,
                                                                 double sampleRate, int blockSize)
{
    String err;
    std::unique_ptr<AudioPluginInstance> p (format.createInstanceFromDescription (desc, sampleRate, blockSize, err));
    if (! p) { std::cerr << "create failed: " << err << "\n"; return nullptr; }

    auto layout = p->getBusesLayout();
    if (! layout.outputBuses.isEmpty())
    {
        layout.outputBuses.set (0, AudioChannelSet::stereo());
        if (! p->setBusesLayout (layout))
            std::cerr << "warning: stereo output layout rejected\n";
    }
    return p;
}

//==============================================================================
// serve-protocol helpers
static String jsonEscape (const String& s)
{
    String r;
    r.preallocateBytes (s.length() * 2 + 8);
    for (int i = 0; i < s.length(); ++i)
    {
        const auto c = s[i];
        switch (c)
        {
            case '"':  r << "\\\""; break;
            case '\\': r << "\\\\"; break;
            case '\n': r << "\\n";   break;
            case '\r': r << "\\r";   break;
            case '\t': r << "\\t";   break;
            default:
                if ((int) c < 0x20)
                    r << "\\u" << String::toHexString ((int) c).paddedLeft ('0', 4);
                else
                    r << c;
                break;
        }
    }
    return r;
}

// Prints one protocol line to stdout and flushes it immediately.
static void protocolLine (const String& s)
{
    std::cout << s.toRawUTF8() << std::endl;
    std::cout.flush();
}

// A single-line sanitised text (protocol lines must stay on one line).
static String oneLine (const String& s)
{
    return s.replace ("\r", " ").replace ("\n", " ").trim();
}

// Resolves a parameter by ID (preferred) or by decimal index.
static AudioProcessorParameter* findParameter (AudioPluginInstance& inst, const String& keyIn)
{
    const String key = keyIn.trim();
    if (key.isEmpty()) return nullptr;

    auto& params = inst.getParameters();

    for (auto* p : params)
        if (p != nullptr)
            if (auto* hosted = dynamic_cast<AudioPluginInstance::HostedParameter*> (p))
                if (hosted->getParameterID() == key)
                    return p;

    if (key.containsOnly ("0123456789"))
    {
        const int idx = key.getIntValue();
        if (idx >= 0 && idx < params.size())
            return params[idx];
    }
    return nullptr;
}

static String parameterId (AudioProcessorParameter* p, int index)
{
    if (p != nullptr)
        if (auto* hosted = dynamic_cast<AudioPluginInstance::HostedParameter*> (p))
        {
            auto id = hosted->getParameterID();
            if (id.isNotEmpty()) return id;
        }
    return String (index);
}

// Builds the parameter table JSON (UTF-8 text, no BOM).
static String buildParamsJson (AudioPluginInstance& inst)
{
    auto& params = inst.getParameters();
    String j;
    j << "{\"plugin\":\"" << jsonEscape (inst.getName()) << "\",\"count\":" << params.size()
      << ",\"params\":[";
    for (int i = 0; i < params.size(); ++i)
    {
        auto* p = params[i];
        if (p == nullptr) continue;
        const String id    = parameterId (p, i);
        const String name  = oneLine (p->getName (256));
        const String text  = oneLine (p->getCurrentValueAsText());
        const String label = oneLine (p->getLabel());
        j << (i > 0 ? "," : "")
          << "{\"index\":" << i
          << ",\"id\":\"" << jsonEscape (id) << "\""
          << ",\"name\":\"" << jsonEscape (name) << "\""
          << ",\"text\":\"" << jsonEscape (text) << "\""
          << ",\"norm\":" << String ((double) p->getValue(), 6)
          << ",\"label\":\"" << jsonEscape (label) << "\""
          << ",\"steps\":" << p->getNumSteps()
          << "}";
    }
    j << "]}";
    return j;
}

// Writes the current plugin state as a .vstpreset file (shared by -save and SAVE|)
static bool writeVstPreset (AudioProcessor& inst, const String& savePath,
                            const String& basePresetPath, String& errorOut)
{
    if (savePath.trim().isEmpty()) { errorOut = "empty path"; return false; }

    juce::MemoryBlock mb;
    inst.getStateInformation (mb);

    juce::MemoryBlock compB, contB;
    if (auto xml = juce::AudioProcessor::getXmlFromBinary (mb.getData(), (int) mb.getSize()))
    {
        if (auto* e = xml->getChildByName ("IComponent"))      compB.fromBase64Encoding (e->getAllSubText());
        if (auto* e = xml->getChildByName ("IEditController")) contB.fromBase64Encoding (e->getAllSubText());
    }
    if (compB.getSize() == 0) { errorOut = "no component state"; return false; }

    std::vector<char> out (48, 0);
    if (basePresetPath.isNotEmpty() && juce::File (basePresetPath).existsAsFile())
    {
        juce::FileInputStream fi { juce::File (basePresetPath) };
        fi.read (out.data(), 48);
    }
    else { memcpy (out.data(), "VST3", 4); }

    auto put = [&] (const juce::MemoryBlock& b) -> std::pair<int64_t, int64_t>
    {
        int64_t off = (int64_t) out.size();
        out.insert (out.end(), (const char*) b.getData(), (const char*) b.getData() + b.getSize());
        return { off, (int64_t) b.getSize() };
    };
    auto comp = put (compB);
    auto cont = put (contB);
    auto info = put (juce::MemoryBlock());

    size_t tbl = out.size();
    out.resize (out.size() + 60, 0);
    auto wr = [&] (int i, const char* id, int64_t off, int64_t sz)
    {
        memcpy (out.data() + tbl + i * 20, id, 4);
        memcpy (out.data() + tbl + i * 20 + 4, &off, 8);
        memcpy (out.data() + tbl + i * 20 + 12, &sz, 8);
    };
    wr (0, "Comp", comp.first, comp.second);
    wr (1, "Cont", cont.first, cont.second);
    wr (2, "Info", info.first, info.second);

    juce::File target (savePath);
    target.getParentDirectory().createDirectory();
    if (! target.replaceWithData (out.data(), out.size()))
    {
        errorOut = "cannot write " + savePath;
        return false;
    }
    std::cerr << "saved preset: " << savePath << " (" << out.size() << " bytes)\n";
    return true;
}

// Renders one file. Output keeps the input's sample rate and bit depth.
static bool renderFile (AudioPluginInstance& inst, AudioFormatManager& fm, const String& inPath,
                        const String& outPath, int outCh, int blockSize, double& curSr,
                        const std::function<void (int)>& onProgress = {})
{
    std::unique_ptr<AudioFormatReader> reader (fm.createReaderFor (File (inPath)));
    if (!reader) { std::cerr << "cannot read " << inPath << "\n"; return false; }
    const double sr = reader->sampleRate;
    const int64_t totalFrames = reader->lengthInSamples;
    // keep bit depth: 16/24/32 supported by WAV writer, 8-bit gets promoted to 16
    int outBits = reader->bitsPerSample;
    if (outBits <= 8) outBits = 16;
    else if (outBits != 16 && outBits != 24 && outBits != 32) outBits = 24;

    if (sr != curSr)
    {
        inst.prepareToPlay (sr, blockSize);
        curSr = sr;
    }
    std::cerr << "input: " << sr << " Hz, " << reader->numChannels << " ch, "
              << outBits << " bit, " << totalFrames << " frames -> " << outPath << "\n";

    WavAudioFormat wav;
    // A re-render has to replace the previous file: opening an existing path keeps its bytes,
    // so without this the new WAV is appended behind the old one and the file keeps growing.
    File (outPath).deleteFile();
    std::unique_ptr<FileOutputStream> outStream (File (outPath).createOutputStream());
    if (!outStream) { std::cerr << "cannot create output\n"; return false; }
    std::unique_ptr<AudioFormatWriter> writer (wav.createWriterFor (outStream.get(), sr, (unsigned int) outCh,
                                                                   (unsigned int) outBits, {}, 0));
    if (!writer) { std::cerr << "cannot create wav writer\n"; return false; }
    outStream.release();

    AudioBuffer<float> buf (outCh, blockSize);
    MidiBuffer midi;
    AudioBuffer<float> inBuf ((int) reader->numChannels, blockSize);
    int64_t pos = 0;
    int lastProgress = 0;  // no PROG 0; first report is at 10%
    while (pos < totalFrames)
    {
        int n = (int) jmin<int64_t> (blockSize, totalFrames - pos);
        inBuf.clear();
        reader->read (&inBuf, 0, n, pos, true, true);
        buf.clear();
        for (int ch = 0; ch < outCh; ++ch)
        {
            int srcCh = jmin (ch, inBuf.getNumChannels() - 1);
            buf.copyFrom (ch, 0, inBuf, srcCh, 0, n);
        }
        midi.clear();
        inst.processBlock (buf, midi);
        const float* chans[32];
        for (int ch = 0; ch < outCh && ch < 32; ++ch) chans[ch] = buf.getReadPointer (ch);
        writer->writeFromFloatArrays (chans, outCh, n);
        pos += n;

        if (onProgress)
        {
            const int pct10 = totalFrames > 0 ? (int) ((pos * 10) / totalFrames) * 10 : 100;
            if (pct10 > lastProgress)
            {
                lastProgress = pct10;
                onProgress (jmin (100, pct10));
            }
        }
    }
    writer.reset();
    std::cerr << "done: " << totalFrames << " frames\n";
    return true;
}

int main (int argc, char** argv)
{
    ScopedJuceInitialiser_GUI juceInit;
    String pluginPath, presetPath, savePresetPath;
    std::vector<String> inPaths, outPaths;
    int blockSize = 512;
    bool serveMode = false;
    for (int i = 1; i < argc; ++i)
    {
        String a (argv[i]);
        auto next = [&]() -> String { return (i + 1 < argc) ? String (argv[++i]) : String(); };
        if (a == "-plugin") pluginPath = next();
        else if (a == "-preset") presetPath = next();
        else if (a == "-in") inPaths.push_back (next());
        else if (a == "-out") outPaths.push_back (next());
        else if (a == "-save") savePresetPath = next();
        else if (a == "-serve") serveMode = true;
        else if (a == "-block") blockSize = jmax (32, jmin (8192, next().getIntValue()));
    }
    const bool hasRender = ! inPaths.empty() && inPaths.size() == outPaths.size();
    if (pluginPath.isEmpty() || (! hasRender && savePresetPath.isEmpty() && ! serveMode))
    {
        std::cerr << "usage: vstrender_juce -plugin <bundle.vst3> [-preset <p.vstpreset>] "
                     "-in <a.wav> [-in <b.wav> ...] -out <a_out.wav> [-out <b_out.wav> ...] "
                     "[-block 512] [-save <out.vstpreset>] [-serve]\n";
        return 2;
    }

    AudioFormatManager fm;
    fm.registerBasicFormats();

    double curSr = 48000.0;
    if (hasRender)
    {
        std::unique_ptr<AudioFormatReader> first (fm.createReaderFor (File (inPaths[0])));
        if (! first) { std::cerr << "cannot read input wav\n"; return 3; }
        curSr = first->sampleRate;
    }

    AudioPluginFormatManager pfm;
    addDefaultFormatsToManager (pfm);
    VST3PluginFormat* vst3 = nullptr;
    for (auto* f : pfm.getFormats())
        if (auto* v = dynamic_cast<VST3PluginFormat*> (f)) { vst3 = v; break; }
    if (!vst3) { std::cerr << "no VST3 format\n"; return 4; }

    OwnedArray<PluginDescription> descs;
    vst3->findAllTypesForFile (descs, pluginPath);
    if (descs.isEmpty()) { std::cerr << "plugin not found: " << pluginPath << "\n"; return 5; }
    std::cerr << "plugin: " << descs[0]->name << " (" << descs[0]->pluginFormatName << ")\n";

    auto inst = createHostedInstance (*vst3, *descs[0], curSr, blockSize);
    if (! inst) return 6;
    inst->prepareToPlay (curSr, blockSize);

    PresetChunks pc;
    if (presetPath.isNotEmpty())
    {
        if (readPreset (presetPath, pc))
        {
            applyPreset (*inst, pc);
            std::cerr << "preset applied: comp=" << pc.comp.size() << " cont=" << pc.cont.size() << "\n";
        }
        else std::cerr << "warning: preset read failed\n";
    }

    // resident mode: one "|"-separated command per stdin line, processed sequentially.
    //   legacy render : in|out[|preset]
    //   PARAMS|<jsonPath>         write parameter table JSON, prints "OK <path>"
    //   GET|<idOrIndex>           prints "OK <norm>|<text>"
    //   SET|<idOrIndex>|<0..1>    prints "OK <text>"
    //   PRESET|<vstpresetPath>    load a preset, prints "OK"
    //   SAVE|<vstpresetPath>      save current state as .vstpreset, prints "OK <path>"
    //   RESET[|soft|hard]         reset the plugin so the next render starts clean; keeps
    //                             parameters/preset, prints "OK"
    if (serveMode)
    {
        int outCh = jmax (1, inst->getTotalNumOutputChannels());
        std::cerr << "serve ready, out " << outCh << " ch\n";
        protocolLine ("READY");

        // RESET rebuilds the instance by default (measured: inst->reset() alone leaves audible
        // differences for this plug-in). Set to false to default to the cheap reset() path.
        static constexpr bool resetDefaultsToHard = true;

        // Soft reset: AudioProcessor::reset() only (the VST3 instance reactivates the component).
        // Cheapest possible path, but its effect on plugin-internal DSP state is plugin-defined.
        auto softReset = [&] () { inst->reset(); };

        // Hard reset: destroy and recreate the instance, then restore the full component state
        // (all parameters / loaded preset / runtime SET values), so the next render starts from
        // the same DSP state as a freshly launched process.
        auto hardReset = [&] () -> bool
        {
            juce::MemoryBlock state;
            inst->getStateInformation (state);
            auto fresh = createHostedInstance (*vst3, *descs[0], curSr, blockSize);
            if (! fresh) return false;
            fresh->setStateInformation (state.getData(), (int) state.getSize());
            fresh->prepareToPlay (curSr, blockSize);
            inst = std::move (fresh);
            outCh = jmax (1, inst->getTotalNumOutputChannels());
            return true;
        };

        std::string line;
        while (std::getline (std::cin, line))
        {
            if (line.empty()) continue;
            if (line == "QUIT" || line == "quit" || line == "Quit") break;

            try
            {
                auto parts = StringArray::fromTokens (String (line.c_str()), "|", "");
                for (int i = 0; i < parts.size(); ++i) parts.set (i, parts[i].trim());
                if (parts.size() < 1 || parts[0].isEmpty()) { protocolLine ("ERR bad line"); continue; }

                const String cmd = parts[0].toUpperCase();

                if (cmd == "PARAMS")
                {
                    if (parts.size() < 2 || parts[1].isEmpty()) { protocolLine ("ERR usage PARAMS|<jsonPath>"); continue; }
                    const juce::File json (parts[1]);
                    json.getParentDirectory().createDirectory();
                    if (! json.replaceWithText (buildParamsJson (*inst), false, false))
                    {
                        protocolLine ("ERR cannot write " + parts[1]);
                        continue;
                    }
                    protocolLine ("OK " + parts[1]);
                    continue;
                }

                if (cmd == "GET")
                {
                    if (parts.size() < 2 || parts[1].isEmpty()) { protocolLine ("ERR usage GET|<idOrIndex>"); continue; }
                    auto* p = findParameter (*inst, parts[1]);
                    if (p == nullptr) { protocolLine ("ERR no param " + parts[1]); continue; }
                    protocolLine ("OK " + String ((double) jlimit (0.0f, 1.0f, p->getValue()), 6)
                                        + "|" + oneLine (p->getCurrentValueAsText()));
                    continue;
                }

                if (cmd == "SET")
                {
                    if (parts.size() < 3 || parts[1].isEmpty()) { protocolLine ("ERR usage SET|<idOrIndex>|<0..1>"); continue; }
                    auto* p = findParameter (*inst, parts[1]);
                    if (p == nullptr) { protocolLine ("ERR no param " + parts[1]); continue; }
                    const String valStr = parts[2];
                    if (valStr.isEmpty() || ! valStr.containsOnly ("0123456789+-.eE"))
                    { protocolLine ("ERR bad value " + valStr); continue; }
                    p->setValueNotifyingHost (jlimit (0.0f, 1.0f, valStr.getFloatValue()));
                    protocolLine ("OK " + oneLine (p->getCurrentValueAsText()));
                    continue;
                }

                if (cmd == "PRESET")
                {
                    if (parts.size() < 2 || parts[1].isEmpty()) { protocolLine ("ERR usage PRESET|<vstpresetPath>"); continue; }
                    PresetChunks np;
                    if (! readPreset (parts[1], np)) { protocolLine ("ERR preset " + parts[1]); continue; }
                    applyPreset (*inst, np);
                    std::cerr << "preset: " << parts[1] << "\n";
                    protocolLine ("OK");
                    continue;
                }

                if (cmd == "SAVE")
                {
                    if (parts.size() < 2 || parts[1].isEmpty()) { protocolLine ("ERR usage SAVE|<vstpresetPath>"); continue; }
                    String saveError;
                    if (writeVstPreset (*inst, parts[1], presetPath, saveError))
                        protocolLine ("OK " + parts[1]);
                    else
                        protocolLine ("ERR " + saveError);
                    continue;
                }

                // RESET[|soft|hard] - the next render starts from a freshly initialised plugin
                // while all component state (parameters, loaded preset, runtime SET values) is kept.
                if (cmd == "RESET")
                {
                    const String mode = parts.size() > 1 ? parts[1].toLowerCase() : String();
                    if (mode.isNotEmpty() && mode != "soft" && mode != "hard")
                    { protocolLine ("ERR usage RESET[|soft|hard]"); continue; }
                    const bool hard = mode == "hard" || (mode.isEmpty() && resetDefaultsToHard);
                    if (hard)
                    {
                        if (hardReset()) protocolLine ("OK");
                        else protocolLine ("ERR reset");
                    }
                    else
                    {
                        softReset();
                        protocolLine ("OK");
                    }
                    continue;
                }

                // legacy render line: in|out[|preset]
                if (parts.size() < 2 || parts[0].isEmpty() || parts[1].isEmpty())
                { protocolLine ("ERR bad line"); continue; }

                const String in = parts[0], out = parts[1];
                const String pr = parts.size() > 2 ? parts[2] : String();
                if (pr.isNotEmpty())
                {
                    PresetChunks np;
                    if (readPreset (pr, np)) { applyPreset (*inst, np); std::cerr << "preset: " << pr << "\n"; }
                    else { protocolLine ("ERR preset " + pr); continue; }
                }
                double sr = curSr;
                const bool rendered = renderFile (*inst, fm, in, out, outCh, blockSize, sr,
                                                  [] (int pct) { protocolLine ("PROG " + String (pct)); });
                if (rendered)
                {
                    if (sr != curSr && ! pc.comp.empty()) applyPreset (*inst, pc);
                    curSr = sr;
                    protocolLine ("OK " + out);
                }
                else protocolLine ("ERR render " + in);
            }
            catch (const std::exception& e)
            {
                protocolLine (String ("ERR exception: ") + e.what());
            }
            catch (...)
            {
                protocolLine ("ERR exception");
            }
        }
        inst->releaseResources();
        return 0;
    }

    if (savePresetPath.isNotEmpty())
    {
        String saveError;
        writeVstPreset (*inst, savePresetPath, presetPath, saveError);
        if (! hasRender) return 0;
    }

    const int outCh = jmax (1, inst->getTotalNumOutputChannels());
    std::cerr << "buses: out " << outCh << " ch\n";

    int ok = 0;
    for (size_t i = 0; i < inPaths.size(); ++i)
    {
        double sr = curSr;
        if (renderFile (*inst, fm, inPaths[i], outPaths[i], outCh, blockSize, sr))
        {
            if (sr != curSr && ! pc.comp.empty()) applyPreset (*inst, pc);
            curSr = sr;
            ++ok;
        }
    }
    inst->releaseResources();
    std::cerr << "batch complete: " << ok << "/" << inPaths.size() << " files\n";
    return ok == (int) inPaths.size() ? 0 : 9;
}

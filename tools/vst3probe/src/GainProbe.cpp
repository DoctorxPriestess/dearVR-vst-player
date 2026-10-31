#include <juce_audio_utils/juce_audio_utils.h>

// Diagnostic probe: halves input, writes an unmistakable marker, and mirrors what it
// wrote back into probe.log so we can tell "write lost" apart from "different memory".
class GainProcessor final : public juce::AudioProcessor
{
public:
    GainProcessor() : AudioProcessor (BusesProperties()
        .withInput ("Input", juce::AudioChannelSet::stereo(), true)
        .withOutput ("Output", juce::AudioChannelSet::stereo(), true)) {}

    const juce::String getName() const override { return "GainProbe"; }
    bool acceptsMidi() const override { return false; }
    bool producesMidi() const override { return false; }
    double getTailLengthSeconds() const override { return 0.0; }
    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram (int) override {}
    const juce::String getProgramName (int) override { return {}; }
    void changeProgramName (int, const juce::String&) override {}

    void prepareToPlay (double sr, int block) override
    {
        log ("PREP sr=" + juce::String (sr) + " block=" + juce::String (block));
    }
    void releaseResources() override {}

    void processBlock (juce::AudioBuffer<float>& buffer, juce::MidiBuffer&) override
    {
        const juce::ScopedNoDenormals noDenormals;
        const int n = buffer.getNumSamples();
        const int nc = buffer.getNumChannels();

        float* w0 = nc > 0 ? buffer.getWritePointer (0) : nullptr;
        const float before = w0 ? w0[0] : 0.0f;

        if (calls < 4)
            log ("PB n=" + juce::String (n) + " ch=" + juce::String (nc)
                 + " in0=" + juce::String (before)
                 + " ptr=" + ptrHex (w0) + " ptr1=" + ptrHex (nc > 1 ? buffer.getWritePointer (1) : nullptr));
        ++calls;

        for (int ch = 0; ch < nc; ++ch)
            buffer.applyGain (ch, 0, n, 0.5f);

        if (w0 != nullptr && n > 0)
        {
            w0[0] = 0.25f;                    // scalar store, no SIMD involved
            if (calls <= 4)
                log ("AFTER write: w0[0]=" + juce::String (w0[0])
                     + " getSample=" + juce::String (buffer.getSample (0, 0))
                     + " gain_in0=" + juce::String (before * 0.5f));
        }
    }

    bool isBusesLayoutSupported (const BusesLayout& layouts) const override
    {
        return layouts.getMainOutputChannelSet() == juce::AudioChannelSet::stereo()
            || layouts.getMainOutputChannelSet() == juce::AudioChannelSet::mono();
    }

    bool hasEditor() const override { return false; }
    juce::AudioProcessorEditor* createEditor() override { return nullptr; }
    void getStateInformation (juce::MemoryBlock&) override {}
    void setStateInformation (const void*, int) override {}

private:
    static juce::String ptrHex (const void* p)
    {
        return "0x" + juce::String::toHexString (
                static_cast<juce::int64> (reinterpret_cast<juce::pointer_sized_int> (p)));
    }

    static void log (const juce::String& s)
    {
        const juce::String bs (juce::String::charToString ((juce::juce_wchar) 92));
        juce::File f ("Z:" + bs + "opt" + bs + "vstenv" + bs + "work" + bs + "probe.log");
        f.appendText (s + "\n");
    }

    int calls = 0;
    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (GainProcessor)
};

juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter()
{
    return new GainProcessor();
}

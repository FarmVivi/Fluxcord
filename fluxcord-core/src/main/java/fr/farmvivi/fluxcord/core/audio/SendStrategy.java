package fr.farmvivi.fluxcord.core.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame decision of the send side of an {@link AudioPipeline}: given which sources can provide audio
 * this frame and in which format, decide whether to relay one source untouched (bypass), mix the PCM ones,
 * or send nothing. Pure function, no state, no JDA — the pipeline owns the fades and the buffers.
 *
 * <p>Rules, in order:
 * <ul>
 *   <li>no active source → {@link Mode#SILENT};</li>
 *   <li>exactly one active PCM source → {@link Mode#BYPASS} of that source (PCM output);</li>
 *   <li>two or more active PCM sources → {@link Mode#MIX} (active Opus sources are dropped for the frame,
 *       they cannot be mixed);</li>
 *   <li>only Opus sources active → {@link Mode#BYPASS} of the highest-priority one (Opus output).</li>
 * </ul>
 * Independently, the active source with the highest priority at or above the threshold is reported as
 * {@link Decision#duckingSource()} so the pipeline can fade the others out.
 */
final class SendStrategy {

    enum Mode { SILENT, BYPASS, MIX }

    /** What the pipeline learned about one source for the current frame. */
    record SourceState(String name, boolean canProvide, boolean opus, int priority) { }

    /**
     * @param mode          how the frame is produced
     * @param bypassSource  the relayed source in {@link Mode#BYPASS}, else {@code null}
     * @param opusOutput    whether the frame handed to JDA is Opus (only in Opus bypass)
     * @param mixSources    the PCM sources to mix in {@link Mode#MIX}, in registration order, else empty
     * @param duckingSource the active source whose priority reaches the threshold (highest wins), else {@code null}
     */
    record Decision(Mode mode, String bypassSource, boolean opusOutput, List<String> mixSources, String duckingSource) {
        static final Decision SILENT = new Decision(Mode.SILENT, null, false, List.of(), null);
    }

    private SendStrategy() { }

    static Decision decide(List<SourceState> sources, int priorityThreshold) {
        List<String> pcm = new ArrayList<>(2);
        String bestOpus = null;
        int bestOpusPriority = Integer.MIN_VALUE;
        String ducking = null;
        int duckingPriority = Integer.MIN_VALUE;

        for (SourceState source : sources) {
            if (!source.canProvide()) {
                continue;
            }
            if (source.opus()) {
                if (source.priority() > bestOpusPriority) {
                    bestOpus = source.name();
                    bestOpusPriority = source.priority();
                }
            } else {
                pcm.add(source.name());
            }
            if (source.priority() >= priorityThreshold && source.priority() > duckingPriority) {
                ducking = source.name();
                duckingPriority = source.priority();
            }
        }

        if (pcm.size() >= 2) {
            return new Decision(Mode.MIX, null, false, List.copyOf(pcm), ducking);
        }
        if (pcm.size() == 1) {
            return new Decision(Mode.BYPASS, pcm.get(0), false, List.of(), ducking);
        }
        if (bestOpus != null) {
            return new Decision(Mode.BYPASS, bestOpus, true, List.of(), ducking);
        }
        return Decision.SILENT;
    }
}

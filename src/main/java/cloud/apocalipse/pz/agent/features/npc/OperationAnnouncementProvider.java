package cloud.apocalipse.pz.agent.features.npc;

import java.util.List;
import cloud.apocalipse.pz.agent.features.lua.events.ApocBREventControl;
import cloud.apocalipse.pz.agent.features.npc.brain.HumanAnnouncementProvider;

/** Event-content adapter. Actor role selection belongs to the composition root. */
final class OperationAnnouncementProvider implements HumanAnnouncementProvider {
    @Override
    public List<String> lines() { return ApocBREventControl.guideAnnouncementLines(); }
}

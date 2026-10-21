package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.events.ApocBREventControl;
import zombie.apocalipse.humans.brain.HumanAnnouncementProvider;

/** Event-content adapter. Actor role selection belongs to the composition root. */
final class OperationAnnouncementProvider implements HumanAnnouncementProvider {
    @Override
    public List<String> lines() { return ApocBREventControl.guideAnnouncementLines(); }
}

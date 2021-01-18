package com.esotericsoftware.SpineStandard;

import com.QYun.SuperSpineViewer.RuntimesLoader;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.Pool.Poolable;
import com.esotericsoftware.SpineStandard.Animation.*;

import java.lang.StringBuilder;

import static com.esotericsoftware.SpineStandard.Animation.RotateTimeline.*;

public class AnimationState {
    static private final Animation emptyAnimation = new Animation("<empty>", new Array<>(0), 0);
    static private final int SUBSEQUENT = 0, FIRST = 1;
    static private final int DIP = 2, DIP_MIX = 3; // Spine36
    static private final int HOLD = 2, HOLD_SUBSEQUENT = 2, HOLD_FIRST = 3; // Spine37
    static private final int SETUP = 1, CURRENT = 2; // Spine38
    static private int HOLD_MIX;
    final Array<TrackEntry> tracks = new Array<>();
    final Array<AnimationStateListener> listeners = new Array<>();
    final Pool<TrackEntry> trackEntryPool = new Pool() { // Spine37/6
        protected Object newObject() {
            return new TrackEntry();
        }
    };
    private final Array<Event> events = new Array<>();
    private final EventQueue queue = new EventQueue();
    private final IntSet propertyIDs = new IntSet();
    private final Array<TrackEntry> mixingTo = new Array<>(); // Spine36
    boolean animationsChanged;
    private AnimationStateData data;
    private float timeScale = 1;
    private int unkeyedState;

    public AnimationState() {
    }

    public AnimationState(AnimationStateData data) {
        if (data == null) throw new IllegalArgumentException("data cannot be null.");
        this.data = data;
        switch (RuntimesLoader.spineVersion.get()) {
            case 38 -> HOLD_MIX = 4;
            case 37 -> HOLD_MIX = 3;
        }
    }

    public void update(float delta) {
        delta *= timeScale;
        for (int i = 0, n = tracks.size; i < n; i++) {
            TrackEntry current = tracks.get(i);
            if (current == null) continue;
            current.animationLast = current.nextAnimationLast;
            current.trackLast = current.nextTrackLast;
            float currentDelta = delta * current.timeScale;
            if (current.delay > 0) {
                current.delay -= currentDelta;
                if (current.delay > 0) continue;
                currentDelta = -current.delay;
                current.delay = 0;
            }
            TrackEntry next = current.next;
            if (next != null) {
                float nextTime = current.trackLast - next.delay;
                if (nextTime >= 0) {
                    next.delay = 0;
                    switch (RuntimesLoader.spineVersion.get()) {
                        case 38 -> next.trackTime += current.timeScale == 0 ? 0 : (nextTime / current.timeScale + delta) * next.timeScale;
                        case 37 -> next.trackTime = current.timeScale == 0 ? 0 : (nextTime / current.timeScale + delta) * next.timeScale;
                        case 36, 35 -> next.trackTime = nextTime + delta * next.timeScale;
                    }
                    current.trackTime += currentDelta;
                    setCurrent(i, next, true);
                    while (next.mixingFrom != null) {
                        switch (RuntimesLoader.spineVersion.get()) {
                            case 38, 37 -> next.mixTime += delta;
                            case 36, 35 -> next.mixTime += currentDelta;
                        }
                        next = next.mixingFrom;
                    }
                    continue;
                }
            } else if (current.trackLast >= current.trackEnd && current.mixingFrom == null) {
                tracks.set(i, null);
                queue.end(current);
                disposeNext(current);
                continue;
            }
            switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37, 36 -> {
                    if (current.mixingFrom != null && updateMixingFrom(current, delta)) {
                        TrackEntry from = current.mixingFrom;
                        current.mixingFrom = null;
                        if (from != null) from.mixingTo = null;
                        while (from != null) {
                            queue.end(from);
                            from = from.mixingFrom;
                        }
                    }
                }
                case 35 -> updateMixingFrom(current, delta);
            }
            current.trackTime += currentDelta;
        }
        queue.drain();
    }

    private boolean updateMixingFrom(TrackEntry to, float delta) {
        TrackEntry from = to.mixingFrom;
        if (from == null) return true;
        boolean finished = updateMixingFrom(from, delta);
        from.animationLast = from.nextAnimationLast;
        from.trackLast = from.nextTrackLast;
        switch (RuntimesLoader.spineVersion.get()) {
            case 38, 37 -> {
                if (to.mixTime > 0 && to.mixTime >= to.mixDuration) {
                    if (from.totalAlpha == 0 || to.mixDuration == 0) {
                        to.mixingFrom = from.mixingFrom;
                        if (from.mixingFrom != null) from.mixingFrom.mixingTo = to;
                        to.interruptAlpha = from.interruptAlpha;
                        queue.end(from);
                    }
                    return finished;
                }
                to.mixTime += delta;
            }
            case 36 -> {
                if (to.mixTime > 0 && (to.mixTime >= to.mixDuration || to.timeScale == 0)) {
                    if (from.totalAlpha == 0 || to.mixDuration == 0) {
                        to.mixingFrom = from.mixingFrom;
                        to.interruptAlpha = from.interruptAlpha;
                        queue.end(from);
                    }
                    return finished;
                }
                to.mixTime += delta * to.timeScale;
            }
            case 35 -> {
                if (to.mixTime >= to.mixDuration && from.mixingFrom == null && to.mixTime > 0) {
                    to.mixingFrom = null;
                    queue.end(from);
                    return finished;
                }
                to.mixTime += delta * to.timeScale;
            }
        }
        from.trackTime += delta * from.timeScale;
        return false;
    }

    public boolean apply(Skeleton skeleton) {
        if (skeleton == null) throw new IllegalArgumentException("skeleton cannot be null.");
        if (animationsChanged) animationsChanged();
        Array<Event> events = this.events;
        boolean applied = false;
        for (int i = 0, n = tracks.size; i < n; i++) {
            TrackEntry current = tracks.get(i);
            if (current == null || current.delay > 0) continue;
            applied = true;
            MixBlend blend = i == 0 ? MixBlend.first : current.mixBlend;
            MixPose currentPose = i == 0 ? MixPose.current : MixPose.currentLayered; // Spine36
            float mix = current.alpha;
            if (current.mixingFrom != null)
                switch (RuntimesLoader.spineVersion.get()) {
                    case 38, 37 -> mix *= applyMixingFrom(current, skeleton, blend);
                    case 36 -> mix *= applyMixingFrom(current, skeleton, currentPose);
                    case 35 -> mix *= applyMixingFrom(current, skeleton);
                }
            else switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37, 36 -> {
                    if (current.trackTime >= current.trackEnd && current.next == null)
                        mix = 0;
                }
                case 35 -> {
                    if (current.trackTime >= current.trackEnd)
                        mix = 0;
                }
            }

            float animationLast = current.animationLast, animationTime = current.getAnimationTime();
            int timelineCount = current.animation.timelines.size;
            Object[] timelines = current.animation.timelines.items;

            switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37 -> {
                    if ((i == 0 && mix == 1) || blend == MixBlend.add) {
                        for (int ii = 0; ii < timelineCount; ii++) {
                            switch (RuntimesLoader.spineVersion.get()) {
                                case 38 -> {
                                    Object timeline = timelines[ii];
                                    if (timeline instanceof AttachmentTimeline)
                                        applyAttachmentTimeline((AttachmentTimeline) timeline, skeleton, animationTime, blend, true);
                                    else
                                        ((Timeline) timeline).apply(skeleton, animationLast, animationTime, events, mix, blend, MixDirection.in);
                                }
                                case 37 -> ((Timeline) timelines[ii]).apply(skeleton, animationLast, animationTime, events, mix, blend, MixDirection.in);
                            }
                        }
                    } else {
                        int[] timelineMode = current.timelineMode.items;
                        boolean firstFrame = current.timelinesRotation.size != timelineCount << 1;
                        if (firstFrame) current.timelinesRotation.setSize(timelineCount << 1);
                        float[] timelinesRotation = current.timelinesRotation.items;
                        for (int ii = 0; ii < timelineCount; ii++) {
                            Timeline timeline = (Timeline) timelines[ii];
                            MixBlend timelineBlend = timelineMode[ii] == SUBSEQUENT ? blend : MixBlend.setup;
                            if (timeline instanceof RotateTimeline) {
                                applyRotateTimeline((RotateTimeline) timeline, skeleton, animationTime, mix, timelineBlend, timelinesRotation,
                                        ii << 1, firstFrame);
                            } else if (timeline instanceof AttachmentTimeline && RuntimesLoader.spineVersion.get() == 38)
                                applyAttachmentTimeline((AttachmentTimeline) timeline, skeleton, animationTime, blend, true);
                            else
                                timeline.apply(skeleton, animationLast, animationTime, events, mix, timelineBlend, MixDirection.in);
                        }
                    }
                }
                case 36 -> {
                    if (mix == 1) {
                        for (int ii = 0; ii < timelineCount; ii++)
                            ((Timeline) timelines[ii]).apply(skeleton, animationLast, animationTime, events, 1, MixPose.P_setup, MixDirection.in);
                    } else {
                        int[] timelineData = current.timelineData.items;
                        boolean firstFrame = current.timelinesRotation.size == 0;
                        if (firstFrame) current.timelinesRotation.setSize(timelineCount << 1);
                        float[] timelinesRotation = current.timelinesRotation.items;

                        for (int ii = 0; ii < timelineCount; ii++) {
                            Timeline timeline = (Timeline) timelines[ii];
                            MixPose pose = timelineData[ii] >= FIRST ? MixPose.P_setup : currentPose;
                            if (timeline instanceof RotateTimeline)
                                applyRotateTimeline(timeline, skeleton, animationTime, mix, pose, timelinesRotation, ii << 1, firstFrame);
                            else
                                timeline.apply(skeleton, animationLast, animationTime, events, mix, pose, MixDirection.in);
                        }
                    }
                }
                case 35 -> {
                    if (mix == 1) {
                        for (int ii = 0; ii < timelineCount; ii++)
                            ((Timeline) timelines[ii]).apply(skeleton, animationLast, animationTime, events, 1, true, false);
                    } else {
                        boolean firstFrame = current.timelinesRotation.size == 0;
                        if (firstFrame) current.timelinesRotation.setSize(timelineCount << 1);
                        float[] timelinesRotation = current.timelinesRotation.items;

                        boolean[] timelinesFirst = current.timelinesFirst.items;
                        for (int ii = 0; ii < timelineCount; ii++) {
                            Timeline timeline = (Timeline) timelines[ii];
                            if (timeline instanceof RotateTimeline) {
                                applyRotateTimeline(timeline, skeleton, animationTime, mix, timelinesFirst[ii], timelinesRotation,
                                        ii << 1, firstFrame);
                            } else
                                timeline.apply(skeleton, animationLast, animationTime, events, mix, timelinesFirst[ii], false);
                        }
                    }
                }
            }
            queueEvents(current, animationTime);
            events.clear();
            current.nextAnimationLast = animationTime;
            current.nextTrackLast = current.trackTime;
        }

        if (RuntimesLoader.spineVersion.get() == 38) {
            int setupState = unkeyedState + SETUP;
            Object[] slots = skeleton.slots.items;
            for (int i = 0, n = skeleton.slots.size; i < n; i++) {
                Slot slot = (Slot) slots[i];
                if (slot.attachmentState == setupState) {
                    String attachmentName = slot.data.attachmentName;
                    slot.setAttachment(attachmentName == null ? null : skeleton.getAttachment(slot.data.index, attachmentName));
                }
            }
            unkeyedState += 2;
        }

        queue.drain();
        return applied;
    }

    private float applyMixingFrom(TrackEntry to, Skeleton skeleton, MixBlend blend) {
        TrackEntry from = to.mixingFrom;
        if (from.mixingFrom != null) applyMixingFrom(from, skeleton, blend);
        float mix;
        if (to.mixDuration == 0) {
            mix = 1;
            if (blend == MixBlend.first)
                blend = MixBlend.setup;
        } else {
            mix = to.mixTime / to.mixDuration;
            if (mix > 1) mix = 1;
            if (blend != MixBlend.first) blend = from.mixBlend;
        }
        Array<Event> events = mix < from.eventThreshold ? this.events : null;
        boolean attachments = mix < from.attachmentThreshold, drawOrder = mix < from.drawOrderThreshold;
        float animationLast = from.animationLast, animationTime = from.getAnimationTime();
        int timelineCount = from.animation.timelines.size;
        Object[] timelines = from.animation.timelines.items;
        float alphaHold = from.alpha * to.interruptAlpha, alphaMix = alphaHold * (1 - mix);
        if (blend == MixBlend.add) {
            for (int i = 0; i < timelineCount; i++)
                ((Timeline) timelines[i]).apply(skeleton, animationLast, animationTime, events, alphaMix, blend, MixDirection.out);
        } else {
            int[] timelineMode = from.timelineMode.items;
            Object[] timelineHoldMix = from.timelineHoldMix.items;
            boolean firstFrame = from.timelinesRotation.size != timelineCount << 1;
            if (firstFrame) from.timelinesRotation.setSize(timelineCount << 1);
            float[] timelinesRotation = from.timelinesRotation.items;
            from.totalAlpha = 0;
            for (int i = 0; i < timelineCount; i++) {
                Timeline timeline = (Timeline) timelines[i];
                MixDirection direction = MixDirection.out;
                MixBlend timelineBlend = null;
                float alpha;
                switch (timelineMode[i]) {
                    case SUBSEQUENT -> {
                        if (!attachments && timeline instanceof AttachmentTimeline && RuntimesLoader.spineVersion.get() == 37)
                            continue;
                        if (!drawOrder && timeline instanceof DrawOrderTimeline) continue;
                        timelineBlend = blend;
                        alpha = alphaMix;
                    }
                    case FIRST -> {
                        timelineBlend = MixBlend.setup;
                        alpha = alphaMix;
                    }
                    case HOLD -> {
                        switch (RuntimesLoader.spineVersion.get()) {
                            case 38 -> timelineBlend = blend;
                            case 37 -> timelineBlend = MixBlend.setup;
                        }
                        alpha = alphaHold;
                    }
                    case HOLD_FIRST -> {
                        timelineBlend = MixBlend.setup;
                        alpha = alphaHold;
                    }
                    default -> {
                        timelineBlend = MixBlend.setup;
                        TrackEntry holdMix = (TrackEntry) timelineHoldMix[i];
                        alpha = alphaHold * Math.max(0, 1 - holdMix.mixTime / holdMix.mixDuration);
                    }
                }
                from.totalAlpha += alpha;
                if (timeline instanceof RotateTimeline) {
                    applyRotateTimeline((RotateTimeline) timeline, skeleton, animationTime, alpha, timelineBlend, timelinesRotation,
                            i << 1, firstFrame);
                } else if (timeline instanceof AttachmentTimeline && RuntimesLoader.spineVersion.get() == 38)
                    applyAttachmentTimeline((AttachmentTimeline) timeline, skeleton, animationTime, timelineBlend, attachments);
                else {
                    switch (RuntimesLoader.spineVersion.get()) {
                        case 38 -> {
                            if (drawOrder && timeline instanceof DrawOrderTimeline && timelineBlend == MixBlend.setup)
                                direction = MixDirection.in;
                            timeline.apply(skeleton, animationLast, animationTime, events, alpha, timelineBlend, direction);
                        }
                        case 37 -> {
                            if (timelineBlend == MixBlend.setup) {
                                if (timeline instanceof AttachmentTimeline) {
                                    if (attachments) direction = MixDirection.in;
                                } else if (timeline instanceof DrawOrderTimeline) {
                                    if (drawOrder) direction = MixDirection.in;
                                }
                            }
                            timeline.apply(skeleton, animationLast, animationTime, events, alpha, timelineBlend, direction);
                        }
                    }
                }
            }
        }
        if (to.mixDuration > 0) queueEvents(from, animationTime);
        this.events.clear();
        from.nextAnimationLast = animationTime;
        from.nextTrackLast = from.trackTime;
        return mix;
    }

    private float applyMixingFrom(TrackEntry to, Skeleton skeleton, MixPose currentPose) { // Spine36
        TrackEntry from = to.mixingFrom;
        if (from.mixingFrom != null) applyMixingFrom(from, skeleton, currentPose);

        float mix;
        if (to.mixDuration == 0) {
            mix = 1;
            currentPose = MixPose.P_setup;
        } else {
            mix = to.mixTime / to.mixDuration;
            if (mix > 1) mix = 1;
        }

        Array<Event> events = mix < from.eventThreshold ? this.events : null;
        boolean attachments = mix < from.attachmentThreshold, drawOrder = mix < from.drawOrderThreshold;
        float animationLast = from.animationLast, animationTime = from.getAnimationTime();
        int timelineCount = from.animation.timelines.size;
        Object[] timelines = from.animation.timelines.items;
        int[] timelineData = from.timelineData.items;
        Object[] timelineDipMix = from.timelineDipMix.items;

        boolean firstFrame = from.timelinesRotation.size == 0;
        if (firstFrame) from.timelinesRotation.setSize(timelineCount << 1);
        float[] timelinesRotation = from.timelinesRotation.items;

        MixPose pose;
        float alphaDip = from.alpha * to.interruptAlpha, alphaMix = alphaDip * (1 - mix), alpha;
        from.totalAlpha = 0;
        for (int i = 0; i < timelineCount; i++) {
            Timeline timeline = (Timeline) timelines[i];
            switch (timelineData[i]) {
                case SUBSEQUENT -> {
                    if (!attachments && timeline instanceof AttachmentTimeline) continue;
                    if (!drawOrder && timeline instanceof DrawOrderTimeline) continue;
                    pose = currentPose;
                    alpha = alphaMix;
                }
                case FIRST -> {
                    pose = MixPose.P_setup;
                    alpha = alphaMix;
                }
                case DIP -> {
                    pose = MixPose.P_setup;
                    alpha = alphaDip;
                }
                default -> {
                    pose = MixPose.P_setup;
                    TrackEntry dipMix = (TrackEntry) timelineDipMix[i];
                    alpha = alphaDip * Math.max(0, 1 - dipMix.mixTime / dipMix.mixDuration);
                }
            }
            from.totalAlpha += alpha;
            if (timeline instanceof RotateTimeline)
                applyRotateTimeline(timeline, skeleton, animationTime, alpha, pose, timelinesRotation, i << 1, firstFrame);
            else
                timeline.apply(skeleton, animationLast, animationTime, events, alpha, pose, MixDirection.out);
        }

        if (to.mixDuration > 0) queueEvents(from, animationTime);
        this.events.clear();
        from.nextAnimationLast = animationTime;
        from.nextTrackLast = from.trackTime;

        return mix;
    }

    private float applyMixingFrom(TrackEntry to, Skeleton skeleton) { // Spine35
        TrackEntry from = to.mixingFrom;
        if (from.mixingFrom != null) applyMixingFrom(from, skeleton);

        float mix;
        if (to.mixDuration == 0)
            mix = 1;
        else {
            mix = to.mixTime / to.mixDuration;
            if (mix > 1) mix = 1;
        }

        Array<Event> events = mix < from.eventThreshold ? this.events : null;
        boolean attachments = mix < from.attachmentThreshold, drawOrder = mix < from.drawOrderThreshold;
        float animationLast = from.animationLast, animationTime = from.getAnimationTime();
        int timelineCount = from.animation.timelines.size;
        Object[] timelines = from.animation.timelines.items;
        boolean[] timelinesFirst = from.timelinesFirst.items;
        float alpha = from.alpha * to.mixAlpha * (1 - mix);

        boolean firstFrame = from.timelinesRotation.size == 0;
        if (firstFrame) from.timelinesRotation.setSize(timelineCount << 1);
        float[] timelinesRotation = from.timelinesRotation.items;

        for (int i = 0; i < timelineCount; i++) {
            Timeline timeline = (Timeline) timelines[i];
            boolean setupPose = timelinesFirst[i];
            if (timeline instanceof RotateTimeline)
                applyRotateTimeline(timeline, skeleton, animationTime, alpha, setupPose, timelinesRotation, i << 1, firstFrame);
            else {
                if (!setupPose) {
                    if (!attachments && timeline instanceof AttachmentTimeline) continue;
                    if (!drawOrder && timeline instanceof DrawOrderTimeline) continue;
                }
                timeline.apply(skeleton, animationLast, animationTime, events, alpha, setupPose, true);
            }
        }

        if (to.mixDuration > 0) queueEvents(from, animationTime);
        this.events.clear();
        from.nextAnimationLast = animationTime;
        from.nextTrackLast = from.trackTime;

        return mix;
    }

    private void applyAttachmentTimeline(AttachmentTimeline timeline, Skeleton skeleton, float time,
                                         MixBlend blend, boolean attachments) {
        Slot slot = skeleton.slots.get(timeline.slotIndex);
        if (!slot.bone.active) return;
        float[] frames = timeline.frames;
        if (time < frames[0]) {
            if (blend == MixBlend.setup || blend == MixBlend.first)
                setAttachment(skeleton, slot, slot.data.attachmentName, attachments);
        } else {
            int frameIndex;
            if (time >= frames[frames.length - 1])
                frameIndex = frames.length - 1;
            else
                frameIndex = Animation.binarySearch(frames, time) - 1;
            setAttachment(skeleton, slot, timeline.attachmentNames[frameIndex], attachments);
        }
        if (slot.attachmentState <= unkeyedState) slot.attachmentState = unkeyedState + SETUP;
    }

    private void setAttachment(Skeleton skeleton, Slot slot, String attachmentName, boolean attachments) {
        slot.setAttachment(attachmentName == null ? null : skeleton.getAttachment(slot.data.index, attachmentName));
        if (attachments) slot.attachmentState = unkeyedState + CURRENT;
    }

    private void applyRotateTimeline(RotateTimeline timeline, Skeleton skeleton, float time, float alpha, MixBlend blend,
                                     float[] timelinesRotation, int i, boolean firstFrame) {
        if (firstFrame) timelinesRotation[i] = 0;
        if (alpha == 1) {
            timeline.apply(skeleton, 0, time, null, 1, blend, MixDirection.in);
            return;
        }
        Bone bone = skeleton.bones.get(timeline.boneIndex);
        if (RuntimesLoader.spineVersion.get() == 38)
            if (!bone.active) return;
        float[] frames = timeline.frames;
        float r1, r2;
        if (time < frames[0]) {
            switch (blend) {
                case setup:
                    bone.rotation = bone.data.rotation;
                default:
                    return;
                case first:
                    r1 = bone.rotation;
                    r2 = bone.data.rotation;
            }
        } else {
            r1 = blend == MixBlend.setup ? bone.data.rotation : bone.rotation;
            if (time >= frames[frames.length - ENTRIES])
                r2 = bone.data.rotation + frames[frames.length + PREV_ROTATION];
            else {
                int frame = Animation.binarySearch(frames, time, ENTRIES);
                float prevRotation = frames[frame + PREV_ROTATION];
                float frameTime = frames[frame];
                float percent = timeline.getCurvePercent((frame >> 1) - 1,
                        1 - (time - frameTime) / (frames[frame + PREV_TIME] - frameTime));
                r2 = frames[frame + ROTATION] - prevRotation;
                r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
                r2 = prevRotation + r2 * percent + bone.data.rotation;
                r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
            }
        }
        float total, diff = r2 - r1;
        diff -= (16384 - (int) (16384.499999999996 - diff / 360)) * 360;
        if (diff == 0)
            total = timelinesRotation[i];
        else {
            float lastTotal, lastDiff;
            if (firstFrame) {
                lastTotal = 0;
                lastDiff = diff;
            } else {
                lastTotal = timelinesRotation[i];
                lastDiff = timelinesRotation[i + 1];
            }
            boolean current = diff > 0, dir = lastTotal >= 0;
            if (Math.signum(lastDiff) != Math.signum(diff) && Math.abs(lastDiff) <= 90) {
                if (Math.abs(lastTotal) > 180) lastTotal += 360 * Math.signum(lastTotal);
                dir = current;
            }
            total = diff + lastTotal - lastTotal % 360;
            if (dir != current) total += 360 * Math.signum(lastTotal);
            timelinesRotation[i] = total;
        }
        timelinesRotation[i + 1] = diff;
        r1 += total * alpha;
        bone.rotation = r1 - (16384 - (int) (16384.499999999996 - r1 / 360)) * 360;
    }

    private void applyRotateTimeline(Timeline timeline, Skeleton skeleton, float time, float alpha, MixPose pose,
                                     float[] timelinesRotation, int i, boolean firstFrame) { // Spine36
        if (firstFrame) timelinesRotation[i] = 0;

        if (alpha == 1) {
            timeline.apply(skeleton, 0, time, null, 1, pose, MixDirection.in);
            return;
        }

        RotateTimeline rotateTimeline = (RotateTimeline) timeline;
        Bone bone = skeleton.bones.get(rotateTimeline.boneIndex);
        float[] frames = rotateTimeline.frames;
        if (time < frames[0]) {
            if (pose == MixPose.P_setup) bone.rotation = bone.data.rotation;
            return;
        }

        float r2;
        if (time >= frames[frames.length - ENTRIES])
            r2 = bone.data.rotation + frames[frames.length + PREV_ROTATION];
        else {

            int frame = Animation.binarySearch(frames, time, ENTRIES);
            float prevRotation = frames[frame + PREV_ROTATION];
            float frameTime = frames[frame];
            float percent = rotateTimeline.getCurvePercent((frame >> 1) - 1,
                    1 - (time - frameTime) / (frames[frame + PREV_TIME] - frameTime));

            r2 = frames[frame + ROTATION] - prevRotation;
            r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
            r2 = prevRotation + r2 * percent + bone.data.rotation;
            r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
        }

        float r1 = pose == MixPose.P_setup ? bone.data.rotation : bone.rotation;
        float total, diff = r2 - r1;
        if (diff == 0)
            total = timelinesRotation[i];
        else {
            diff -= (16384 - (int) (16384.499999999996 - diff / 360)) * 360;
            float lastTotal, lastDiff;
            if (firstFrame) {
                lastTotal = 0;
                lastDiff = diff;
            } else {
                lastTotal = timelinesRotation[i];
                lastDiff = timelinesRotation[i + 1];
            }
            boolean current = diff > 0, dir = lastTotal >= 0;

            if (Math.signum(lastDiff) != Math.signum(diff) && Math.abs(lastDiff) <= 90) {

                if (Math.abs(lastTotal) > 180) lastTotal += 360 * Math.signum(lastTotal);
                dir = current;
            }
            total = diff + lastTotal - lastTotal % 360;
            if (dir != current) total += 360 * Math.signum(lastTotal);
            timelinesRotation[i] = total;
        }
        timelinesRotation[i + 1] = diff;
        r1 += total * alpha;
        bone.rotation = r1 - (16384 - (int) (16384.499999999996 - r1 / 360)) * 360;
    }

    private void applyRotateTimeline(Timeline timeline, Skeleton skeleton, float time, float alpha, boolean setupPose,
                                     float[] timelinesRotation, int i, boolean firstFrame) { // Spine35
        if (firstFrame) timelinesRotation[i] = 0;

        if (alpha == 1) {
            timeline.apply(skeleton, 0, time, null, 1, setupPose, false);
            return;
        }

        RotateTimeline rotateTimeline = (RotateTimeline) timeline;
        Bone bone = skeleton.bones.get(rotateTimeline.boneIndex);
        float[] frames = rotateTimeline.frames;
        if (time < frames[0]) {
            if (setupPose) bone.rotation = bone.data.rotation;
            return;
        }

        float r2;
        if (time >= frames[frames.length - ENTRIES])
            r2 = bone.data.rotation + frames[frames.length + PREV_ROTATION];
        else {

            int frame = Animation.binarySearch(frames, time, ENTRIES);
            float prevRotation = frames[frame + PREV_ROTATION];
            float frameTime = frames[frame];
            float percent = rotateTimeline.getCurvePercent((frame >> 1) - 1,
                    1 - (time - frameTime) / (frames[frame + PREV_TIME] - frameTime));

            r2 = frames[frame + ROTATION] - prevRotation;
            r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
            r2 = prevRotation + r2 * percent + bone.data.rotation;
            r2 -= (16384 - (int) (16384.499999999996 - r2 / 360)) * 360;
        }

        float r1 = setupPose ? bone.data.rotation : bone.rotation;
        float total, diff = r2 - r1;
        if (diff == 0)
            total = timelinesRotation[i];
        else {
            diff -= (16384 - (int) (16384.499999999996 - diff / 360)) * 360;
            float lastTotal, lastDiff;
            if (firstFrame) {
                lastTotal = 0;
                lastDiff = diff;
            } else {
                lastTotal = timelinesRotation[i];
                lastDiff = timelinesRotation[i + 1];
            }
            boolean current = diff > 0, dir = lastTotal >= 0;

            if (Math.signum(lastDiff) != Math.signum(diff) && Math.abs(lastDiff) <= 90) {

                if (Math.abs(lastTotal) > 180) lastTotal += 360 * Math.signum(lastTotal);
                dir = current;
            }
            total = diff + lastTotal - lastTotal % 360;
            if (dir != current) total += 360 * Math.signum(lastTotal);
            timelinesRotation[i] = total;
        }
        timelinesRotation[i + 1] = diff;
        r1 += total * alpha;
        bone.rotation = r1 - (16384 - (int) (16384.499999999996 - r1 / 360)) * 360;
    }

    private void queueEvents(TrackEntry entry, float animationTime) {
        float animationStart = entry.animationStart, animationEnd = entry.animationEnd;
        float duration = animationEnd - animationStart;
        float trackLastWrapped = entry.trackLast % duration;
        Array<Event> events = this.events;
        int i = 0, n = events.size;
        for (; i < n; i++) {
            Event event = events.get(i);
            if (event.time < trackLastWrapped) break;
            if (event.time > animationEnd) continue;
            queue.event(entry, event);
        }

        switch (RuntimesLoader.spineVersion.get()) {
            case 38, 37, 36 -> {
                boolean complete;
                if (entry.loop)
                    complete = duration == 0 || trackLastWrapped > entry.trackTime % duration;
                else
                    complete = animationTime >= animationEnd && entry.animationLast < animationEnd;
                if (complete) queue.complete(entry);
            }
            case 35 -> {
                if (entry.loop ? (trackLastWrapped > entry.trackTime % duration)
                        : (animationTime >= animationEnd && entry.animationLast < animationEnd))
                    queue.complete(entry);
            }
        }

        for (; i < n; i++) {
            Event event = events.get(i);
            if (event.time < animationStart) continue;
            queue.event(entry, events.get(i));
        }
    }

    public void clearTracks() {
        boolean oldDrainDisabled = queue.drainDisabled;
        queue.drainDisabled = true;
        for (int i = 0, n = tracks.size; i < n; i++)
            clearTrack(i);
        tracks.clear();
        queue.drainDisabled = oldDrainDisabled;
        queue.drain();
    }

    public void clearTrack(int trackIndex) {
        if (trackIndex < 0) throw new IllegalArgumentException("trackIndex must be >= 0.");
        if (trackIndex >= tracks.size) return;
        TrackEntry current = tracks.get(trackIndex);
        if (current == null) return;
        queue.end(current);
        disposeNext(current);
        TrackEntry entry = current;
        while (true) {
            TrackEntry from = entry.mixingFrom;
            if (from == null) break;
            queue.end(from);
            entry.mixingFrom = null;
            switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37, 36 -> entry.mixingTo = null;
            }
            entry = from;
        }
        tracks.set(current.trackIndex, null);
        queue.drain();
    }

    private void setCurrent(int index, TrackEntry current, boolean interrupt) {
        TrackEntry from = expandToIndex(index);
        tracks.set(index, current);
        if (from != null) {
            if (interrupt) queue.interrupt(from);
            current.mixingFrom = from;
            current.mixTime = 0;
            from.timelinesRotation.clear();
            switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37, 36 -> {
                    from.mixingTo = current;
                    if (from.mixingFrom != null && from.mixDuration > 0)
                        current.interruptAlpha *= Math.min(1, from.mixTime / from.mixDuration);
                }
                case 35 -> {
                    if (from.mixingFrom != null && from.mixDuration > 0)
                        current.mixAlpha *= Math.min(from.mixTime / from.mixDuration, 1);
                }
            }
        }
        queue.start(current);
    }

    public TrackEntry setAnimation(int trackIndex, String animationName, boolean loop) {
        Animation animation = data.skeletonData.findAnimation(animationName);
        if (animation == null) throw new IllegalArgumentException("Animation not found: " + animationName);
        return setAnimation(trackIndex, animation, loop);
    }

    public TrackEntry setAnimation(int trackIndex, Animation animation, boolean loop) {
        if (trackIndex < 0) throw new IllegalArgumentException("trackIndex must be >= 0.");
        if (animation == null) throw new IllegalArgumentException("animation cannot be null.");
        boolean interrupt = true;
        TrackEntry current = expandToIndex(trackIndex);
        if (current != null) {
            if (current.nextTrackLast == -1) {
                tracks.set(trackIndex, current.mixingFrom);
                queue.interrupt(current);
                queue.end(current);
                disposeNext(current);
                current = current.mixingFrom;
                interrupt = false;
            } else
                disposeNext(current);
        }
        TrackEntry entry = trackEntry(trackIndex, animation, loop, current);
        setCurrent(trackIndex, entry, interrupt);
        queue.drain();
        return entry;
    }

    public TrackEntry addAnimation(int trackIndex, String animationName, boolean loop, float delay) {
        Animation animation = data.skeletonData.findAnimation(animationName);
        if (animation == null) throw new IllegalArgumentException("Animation not found: " + animationName);
        return addAnimation(trackIndex, animation, loop, delay);
    }

    public TrackEntry addAnimation(int trackIndex, Animation animation, boolean loop, float delay) {
        if (trackIndex < 0) throw new IllegalArgumentException("trackIndex must be >= 0.");
        if (animation == null) throw new IllegalArgumentException("animation cannot be null.");
        TrackEntry last = expandToIndex(trackIndex);
        if (last != null) {
            while (last.next != null)
                last = last.next;
        }
        TrackEntry entry = trackEntry(trackIndex, animation, loop, last);
        if (last == null) {
            setCurrent(trackIndex, entry, true);
            queue.drain();
        } else {
            last.next = entry;
            if (delay <= 0) {
                float duration = last.animationEnd - last.animationStart;
                if (duration != 0) {
                    switch (RuntimesLoader.spineVersion.get()) {
                        case 38, 73, 36 -> {
                            if (last.loop)
                                delay += duration * (1 + (int) (last.trackTime / duration));
                            else switch (RuntimesLoader.spineVersion.get()) {
                                case 38, 37 -> delay += Math.max(duration, last.trackTime);
                                case 36 -> delay += duration;
                            }
                            delay -= data.getMix(last.animation, animation);
                        }
                        case 35 -> {
                            if (duration != 0)
                                delay += duration * (1 + (int) (last.trackTime / duration)) - data.getMix(last.animation, animation);
                        }
                    }
                } else switch (RuntimesLoader.spineVersion.get()) {
                    case 38, 37 -> delay = last.trackTime;
                    case 36, 35 -> delay = 0;
                }
            }
        }
        entry.delay = delay;
        return entry;
    }

    public TrackEntry setEmptyAnimation(int trackIndex, float mixDuration) {
        TrackEntry entry = setAnimation(trackIndex, emptyAnimation, false);
        entry.mixDuration = mixDuration;
        entry.trackEnd = mixDuration;
        return entry;
    }

    public TrackEntry addEmptyAnimation(int trackIndex, float mixDuration, float delay) {
        if (delay <= 0) delay -= mixDuration;
        TrackEntry entry = addAnimation(trackIndex, emptyAnimation, false, delay);
        entry.mixDuration = mixDuration;
        entry.trackEnd = mixDuration;
        return entry;
    }

    public void setEmptyAnimations(float mixDuration) {
        boolean oldDrainDisabled = queue.drainDisabled;
        queue.drainDisabled = true;
        for (int i = 0, n = tracks.size; i < n; i++) {
            TrackEntry current = tracks.get(i);
            if (current != null) setEmptyAnimation(current.trackIndex, mixDuration);
        }
        queue.drainDisabled = oldDrainDisabled;
        queue.drain();
    }

    private TrackEntry expandToIndex(int index) {
        if (index < tracks.size) return tracks.get(index);
        tracks.ensureCapacity(index - tracks.size + 1);
        tracks.size = index + 1;
        return null;
    }

    private TrackEntry trackEntry(int trackIndex, Animation animation, boolean loop, TrackEntry last) {
        TrackEntry entry = trackEntryPool.obtain();
        entry.trackIndex = trackIndex;
        entry.animation = animation;
        entry.loop = loop;
        entry.eventThreshold = 0;
        entry.attachmentThreshold = 0;
        entry.drawOrderThreshold = 0;
        entry.animationStart = 0;
        entry.animationEnd = animation.getDuration();
        entry.animationLast = -1;
        entry.nextAnimationLast = -1;
        entry.delay = 0;
        entry.trackTime = 0;
        entry.trackLast = -1;
        entry.nextTrackLast = -1;
        entry.trackEnd = Float.MAX_VALUE;
        entry.timeScale = 1;
        entry.alpha = 1;
        entry.mixTime = 0;
        entry.mixDuration = last == null ? 0 : data.getMix(last.animation, animation);
        switch (RuntimesLoader.spineVersion.get()) {
            case 38, 37, 36 -> {
                entry.holdPrevious = false;
                entry.interruptAlpha = 1;
            }
            case 35 -> entry.mixAlpha = 1;
        }
        return entry;
    }

    private void disposeNext(TrackEntry entry) {
        TrackEntry next = entry.next;
        while (next != null) {
            queue.dispose(next);
            next = next.next;
        }
        entry.next = null;
    }

    void animationsChanged() {
        animationsChanged = false;
        propertyIDs.clear(2048);

        switch (RuntimesLoader.spineVersion.get()) {
            case 38, 37 -> {
                for (int i = 0, n = tracks.size; i < n; i++) {
                    TrackEntry entry = tracks.get(i);
                    if (entry == null) continue;
                    while (entry.mixingFrom != null)
                        entry = entry.mixingFrom;
                    do {
                        if (entry.mixingTo == null || entry.mixBlend != MixBlend.add) setTimelineModes(entry);
                        entry = entry.mixingTo;
                    } while (entry != null);
                }
            }
            case 36 -> {
                for (int i = 0, n = tracks.size; i < n; i++) {
                    TrackEntry entry = tracks.get(i);
                    if (entry != null) entry.setTimelineData(null, mixingTo, propertyIDs);
                }
            }
            case 35 -> {
                int i = 0, n = tracks.size;
                for (; i < n; i++) {
                    TrackEntry entry = tracks.get(i);
                    if (entry == null) continue;
                    setTimelinesFirst(entry);
                    i++;
                    break;
                }
                for (; i < n; i++) {
                    TrackEntry entry = tracks.get(i);
                    if (entry != null) checkTimelinesFirst(entry);
                }
            }
        }
    }

    private void setTimelinesFirst(TrackEntry entry) { // Spine35
        if (entry.mixingFrom != null) {
            setTimelinesFirst(entry.mixingFrom);
            checkTimelinesUsage(entry);
            return;
        }
        IntSet propertyIDs = this.propertyIDs;
        int n = entry.animation.timelines.size;
        Object[] timelines = entry.animation.timelines.items;
        boolean[] usage = entry.timelinesFirst.setSize(n);
        for (int i = 0; i < n; i++) {
            propertyIDs.add(((Timeline) timelines[i]).getPropertyId());
            usage[i] = true;
        }
    }

    private void checkTimelinesFirst(TrackEntry entry) { // Spine35
        if (entry.mixingFrom != null) checkTimelinesFirst(entry.mixingFrom);
        checkTimelinesUsage(entry);
    }

    private void checkTimelinesUsage(TrackEntry entry) { // Spine35
        IntSet propertyIDs = this.propertyIDs;
        int n = entry.animation.timelines.size;
        Object[] timelines = entry.animation.timelines.items;
        boolean[] usage = entry.timelinesFirst.setSize(n);
        for (int i = 0; i < n; i++)
            usage[i] = propertyIDs.add(((Timeline) timelines[i]).getPropertyId());
    }

    private void setTimelineModes(TrackEntry entry) {
        this.computeHold(entry);
    }

    private void computeHold(TrackEntry entry) {
        TrackEntry to = entry.mixingTo;
        Object[] timelines = entry.animation.timelines.items;
        int timelinesCount = entry.animation.timelines.size;
        int[] timelineMode = entry.timelineMode.setSize(timelinesCount);
        entry.timelineHoldMix.clear();
        Object[] timelineHoldMix = entry.timelineHoldMix.setSize(timelinesCount);
        IntSet propertyIDs = this.propertyIDs;
        if (to != null && to.holdPrevious) {
            if (RuntimesLoader.spineVersion.get() == 38) {
                for (int i = 0; i < timelinesCount; i++)
                    timelineMode[i] = propertyIDs.add(((Timeline) timelines[i]).getPropertyId()) ? HOLD_FIRST : HOLD_SUBSEQUENT;
            } else if (RuntimesLoader.spineVersion.get() == 37) {
                for (int i = 0; i < timelinesCount; i++) {
                    propertyIDs.add(((Timeline) timelines[i]).getPropertyId());
                    timelineMode[i] = HOLD;
                }
            }
            return;
        }
        outer:
        for (int i = 0; i < timelinesCount; i++) {
            if (RuntimesLoader.spineVersion.get() == 38) {
                Timeline timeline = (Timeline) timelines[i];
                int id = timeline.getPropertyId();
                if (!propertyIDs.add(id))
                    timelineMode[i] = SUBSEQUENT;
                else if (to == null || timeline instanceof AttachmentTimeline || timeline instanceof DrawOrderTimeline
                        || timeline instanceof EventTimeline || !to.animation.hasTimeline(id)) {
                    timelineMode[i] = FIRST;
                } else {
                    for (TrackEntry next = to.mixingTo; next != null; next = next.mixingTo) {
                        if (next.animation.hasTimeline(id)) continue;
                        if (next.mixDuration > 0) {
                            timelineMode[i] = HOLD_MIX;
                            timelineHoldMix[i] = next;
                            continue outer;
                        }
                        break;
                    }
                    timelineMode[i] = HOLD_FIRST;
                }
            } else if (RuntimesLoader.spineVersion.get() == 37) {
                int id = ((Timeline) timelines[i]).getPropertyId();
                if (!propertyIDs.add(id))
                    timelineMode[i] = SUBSEQUENT;
                else if (to == null || !hasTimeline(to, id))
                    timelineMode[i] = FIRST;
                else {
                    for (TrackEntry next = to.mixingTo; next != null; next = next.mixingTo) {
                        if (hasTimeline(next, id)) continue;
                        if (next.mixDuration > 0) {
                            timelineMode[i] = HOLD_MIX;
                            timelineHoldMix[i] = next;
                            continue outer;
                        }
                        break;
                    }
                    timelineMode[i] = HOLD;
                }
            }
        }
    }

    private boolean hasTimeline(TrackEntry entry, int id) {
        Object[] timelines = entry.animation.timelines.items;
        for (int i = 0, n = entry.animation.timelines.size; i < n; i++)
            if (((Timeline) timelines[i]).getPropertyId() == id) return true;
        return false;
    }

    public TrackEntry getCurrent(int trackIndex) {
        if (trackIndex < 0) throw new IllegalArgumentException("trackIndex must be >= 0.");
        if (trackIndex >= tracks.size) return null;
        return tracks.get(trackIndex);
    }

    public void addListener(AnimationStateListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
        listeners.add(listener);
    }

    public void removeListener(AnimationStateListener listener) {
        listeners.removeValue(listener, true);
    }

    public void clearListeners() {
        listeners.clear();
    }

    public void clearListenerNotifications() {
        queue.clear();
    }

    public float getTimeScale() {
        return timeScale;
    }

    public void setTimeScale(float timeScale) {
        this.timeScale = timeScale;
    }

    public AnimationStateData getData() {
        return data;
    }

    public void setData(AnimationStateData data) {
        if (data == null) throw new IllegalArgumentException("data cannot be null.");
        this.data = data;
    }

    public Array<TrackEntry> getTracks() {
        return tracks;
    }

    public String toString() {
        StringBuilder buffer = new StringBuilder(64);
        for (int i = 0, n = tracks.size; i < n; i++) {
            TrackEntry entry = tracks.get(i);
            if (entry == null) continue;
            if (buffer.length() > 0) buffer.append(", ");
            buffer.append(entry.toString());
        }
        if (buffer.length() == 0) return "<none>";
        return buffer.toString();
    }

    private enum EventType {
        start, interrupt, end, dispose, complete, event
    }

    public interface AnimationStateListener {
        void start(TrackEntry entry);

        void interrupt(TrackEntry entry);

        void end(TrackEntry entry);

        void dispose(TrackEntry entry);

        void complete(TrackEntry entry);

        void event(TrackEntry entry, Event event);
    }

    static public class TrackEntry implements Poolable {
        final BooleanArray timelinesFirst = new BooleanArray(); // Spine35
        final IntArray timelineMode = new IntArray(), timelineData = new IntArray(); // Spine36
        final Array<TrackEntry> timelineHoldMix = new Array<>(), timelineDipMix = new Array<>(); // Spine36
        final FloatArray timelinesRotation = new FloatArray();
        Animation animation;
        TrackEntry next, mixingFrom, mixingTo;
        AnimationStateListener listener;
        int trackIndex;
        boolean loop, holdPrevious;
        float eventThreshold, attachmentThreshold, drawOrderThreshold;
        float animationStart, animationEnd, animationLast, nextAnimationLast;
        float delay, trackTime, trackLast, nextTrackLast, trackEnd, timeScale;
        float alpha, mixTime, mixDuration, interruptAlpha, totalAlpha, mixAlpha; // Spine35
        MixBlend mixBlend = MixBlend.replace;

        public void reset() {
            next = null;
            mixingFrom = null;
            animation = null;
            listener = null;
            switch (RuntimesLoader.spineVersion.get()) {
                case 38, 37, 36 -> {
                    mixingTo = null;
                    if (RuntimesLoader.spineVersion.get() == 38) {
                        timelineData.clear();
                        timelineDipMix.clear();
                    }
                    timelineMode.clear();
                    timelineHoldMix.clear();
                }
                case 35 -> timelinesFirst.clear();
            }
            timelinesRotation.clear();
        }

        TrackEntry setTimelineData(TrackEntry to, Array<TrackEntry> mixingToArray, IntSet propertyIDs) { // Spine36
            if (to != null) mixingToArray.add(to);
            TrackEntry lastEntry = mixingFrom != null ? mixingFrom.setTimelineData(this, mixingToArray, propertyIDs) : this;
            if (to != null) mixingToArray.pop();

            Object[] mixingTo = mixingToArray.items;
            int mixingToLast = mixingToArray.size - 1;
            Object[] timelines = animation.timelines.items;
            int timelinesCount = animation.timelines.size;
            int[] timelineData = this.timelineData.setSize(timelinesCount);
            timelineDipMix.clear();
            Object[] timelineDipMix = this.timelineDipMix.setSize(timelinesCount);
            outer:
            for (int i = 0; i < timelinesCount; i++) {
                int id = ((Timeline) timelines[i]).getPropertyId();
                if (!propertyIDs.add(id))
                    timelineData[i] = SUBSEQUENT;
                else if (to == null || !to.hasTimeline(id))
                    timelineData[i] = FIRST;
                else {
                    for (int ii = mixingToLast; ii >= 0; ii--) {
                        TrackEntry entry = (TrackEntry) mixingTo[ii];
                        if (!entry.hasTimeline(id)) {
                            if (entry.mixDuration > 0) {
                                timelineData[i] = DIP_MIX;
                                timelineDipMix[i] = entry;
                                continue outer;
                            }
                            break;
                        }
                    }
                    timelineData[i] = DIP;
                }
            }
            return lastEntry;
        }

        private boolean hasTimeline(int id) {
            Object[] timelines = animation.timelines.items;
            for (int i = 0, n = animation.timelines.size; i < n; i++)
                if (((Timeline) timelines[i]).getPropertyId() == id) return true;
            return false;
        }

        public int getTrackIndex() {
            return trackIndex;
        }

        public Animation getAnimation() {
            return animation;
        }

        public void setAnimation(Animation animation) {
            if (animation == null) throw new IllegalArgumentException("animation cannot be null.");
            this.animation = animation;
        }

        public boolean getLoop() {
            return loop;
        }

        public void setLoop(boolean loop) {
            this.loop = loop;
        }

        public float getDelay() {
            return delay;
        }

        public void setDelay(float delay) {
            this.delay = delay;
        }

        public float getTrackTime() {
            return trackTime;
        }

        public void setTrackTime(float trackTime) {
            this.trackTime = trackTime;
        }

        public float getTrackEnd() {
            return trackEnd;
        }

        public void setTrackEnd(float trackEnd) {
            this.trackEnd = trackEnd;
        }

        public float getAnimationStart() {
            return animationStart;
        }

        public void setAnimationStart(float animationStart) {
            this.animationStart = animationStart;
        }

        public float getAnimationEnd() {
            return animationEnd;
        }

        public void setAnimationEnd(float animationEnd) {
            this.animationEnd = animationEnd;
        }

        public float getAnimationLast() {
            return animationLast;
        }

        public void setAnimationLast(float animationLast) {
            this.animationLast = animationLast;
            nextAnimationLast = animationLast;
        }

        public float getAnimationTime() {
            if (loop) {
                float duration = animationEnd - animationStart;
                if (duration == 0) return animationStart;
                return (trackTime % duration) + animationStart;
            }
            return Math.min(trackTime + animationStart, animationEnd);
        }

        public float getTimeScale() {
            return timeScale;
        }

        public void setTimeScale(float timeScale) {
            this.timeScale = timeScale;
        }

        public AnimationStateListener getListener() {
            return listener;
        }

        public void setListener(AnimationStateListener listener) {
            this.listener = listener;
        }

        public float getAlpha() {
            return alpha;
        }

        public void setAlpha(float alpha) {
            this.alpha = alpha;
        }

        public float getEventThreshold() {
            return eventThreshold;
        }

        public void setEventThreshold(float eventThreshold) {
            this.eventThreshold = eventThreshold;
        }

        public float getAttachmentThreshold() {
            return attachmentThreshold;
        }

        public void setAttachmentThreshold(float attachmentThreshold) {
            this.attachmentThreshold = attachmentThreshold;
        }

        public float getDrawOrderThreshold() {
            return drawOrderThreshold;
        }

        public void setDrawOrderThreshold(float drawOrderThreshold) {
            this.drawOrderThreshold = drawOrderThreshold;
        }

        public TrackEntry getNext() {
            return next;
        }

        public boolean isComplete() {
            return trackTime >= animationEnd - animationStart;
        }

        public float getMixTime() {
            return mixTime;
        }

        public void setMixTime(float mixTime) {
            this.mixTime = mixTime;
        }

        public float getMixDuration() {
            return mixDuration;
        }

        public void setMixDuration(float mixDuration) {
            this.mixDuration = mixDuration;
        }

        public MixBlend getMixBlend() {
            return mixBlend;
        }

        public void setMixBlend(MixBlend mixBlend) {
            if (mixBlend == null) throw new IllegalArgumentException("mixBlend cannot be null.");
            this.mixBlend = mixBlend;
        }

        public TrackEntry getMixingFrom() {
            return mixingFrom;
        }

        public TrackEntry getMixingTo() {
            return mixingTo;
        }

        public boolean getHoldPrevious() {
            return holdPrevious;
        }

        public void setHoldPrevious(boolean holdPrevious) {
            this.holdPrevious = holdPrevious;
        }

        public void resetRotationDirections() {
            timelinesRotation.clear();
        }

        public String toString() {
            return animation == null ? "<none>" : animation.name;
        }
    }

    static public abstract class AnimationStateAdapter implements AnimationStateListener {
        public void start(TrackEntry entry) {
        }

        public void interrupt(TrackEntry entry) {
        }

        public void end(TrackEntry entry) {
        }

        public void dispose(TrackEntry entry) {
        }

        public void complete(TrackEntry entry) {
        }

        public void event(TrackEntry entry, Event event) {
        }
    }

    class EventQueue {
        private final Array objects = new Array<>();
        boolean drainDisabled;

        public void start(TrackEntry entry) {
            objects.add(EventType.start);
            objects.add(entry);
            animationsChanged = true;
        }

        public void interrupt(TrackEntry entry) {
            objects.add(EventType.interrupt);
            objects.add(entry);
        }

        public void end(TrackEntry entry) {
            objects.add(EventType.end);
            objects.add(entry);
            animationsChanged = true;
        }

        public void dispose(TrackEntry entry) {
            objects.add(EventType.dispose);
            objects.add(entry);
        }

        public void complete(TrackEntry entry) {
            objects.add(EventType.complete);
            objects.add(entry);
        }

        public void event(TrackEntry entry, Event event) {
            objects.add(EventType.event);
            objects.add(entry);
            objects.add(event);
        }

        public void drain() {
            if (drainDisabled) return;
            drainDisabled = true;
            Array objects = this.objects;
            Array<AnimationStateListener> listeners = AnimationState.this.listeners;
            for (int i = 0; i < objects.size; i += 2) {
                EventType type = (EventType) objects.get(i);
                TrackEntry entry = (TrackEntry) objects.get(i + 1);
                switch (type) {
                    case start:
                        if (entry.listener != null) entry.listener.start(entry);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).start(entry);
                        break;
                    case interrupt:
                        if (entry.listener != null) entry.listener.interrupt(entry);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).interrupt(entry);
                        break;
                    case end:
                        if (entry.listener != null) entry.listener.end(entry);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).end(entry);
                    case dispose:
                        if (entry.listener != null) entry.listener.dispose(entry);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).dispose(entry);
                        trackEntryPool.free(entry);
                        break;
                    case complete:
                        if (entry.listener != null) entry.listener.complete(entry);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).complete(entry);
                        break;
                    case event:
                        Event event = (Event) objects.get(i++ + 2);
                        if (entry.listener != null) entry.listener.event(entry, event);
                        for (int ii = 0; ii < listeners.size; ii++)
                            listeners.get(ii).event(entry, event);
                        break;
                }
            }
            clear();
            drainDisabled = false;
        }

        public void clear() {
            objects.clear();
        }
    }
}

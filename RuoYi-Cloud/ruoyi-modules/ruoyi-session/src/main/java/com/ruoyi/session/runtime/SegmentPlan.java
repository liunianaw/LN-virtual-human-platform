package com.ruoyi.session.runtime;

/** Stable segment identity is allocated before persistence, so C5 operation keys survive a process restart. */
record SegmentPlan(String segmentId, int ordinal, String text) { }

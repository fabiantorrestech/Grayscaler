package io.github.cloudburst.grayscaler

object DraftScheduleSession {
    private val drafts = mutableMapOf<String, Schedule>()

    fun get(id: String): Schedule? = drafts[id]

    fun put(schedule: Schedule) {
        drafts[schedule.id] = schedule
    }

    fun remove(id: String) {
        drafts.remove(id)
    }
}

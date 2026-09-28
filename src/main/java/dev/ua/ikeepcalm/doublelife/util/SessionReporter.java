package dev.ua.ikeepcalm.doublelife.util;

import dev.ua.ikeepcalm.doublelife.DoubleLife;
import dev.ua.ikeepcalm.doublelife.domain.model.RiskAssessment;
import dev.ua.ikeepcalm.doublelife.domain.model.SessionData;
import dev.ua.ikeepcalm.doublelife.domain.model.source.RiskLevel;
import dev.ua.ikeepcalm.doublelife.domain.service.RiskAnalyzer;
import org.bukkit.Bukkit;

/**
 * Orchestrates the end-of-session reporting pipeline:
 * 1. Write the local log file.
 * 2. Score the session via RiskAnalyzer.
 * 3. If score >= threshold, call Gemini for a verdict, then post a flagged alert.
 * 4. Otherwise, post a quiet clean-session summary (if enabled).
 *
 * All network and I/O work runs off the main thread.
 */
public class SessionReporter {

    private final DoubleLife plugin;
    private final RiskAnalyzer riskAnalyzer;

    public SessionReporter(DoubleLife plugin) {
        this.plugin = plugin;
        this.riskAnalyzer = new RiskAnalyzer(plugin);
    }

    /** Scores the session on the calling (main) thread; used for the report and the exited audit row. */
    public RiskAssessment analyze(SessionData session) {
        return riskAnalyzer.analyze(session);
    }

    /**
     * Entry point called from SessionManager.endSession (main thread is fine — async tasks are spawned internally).
     * While the plugin is disabling, Bukkit refuses new async tasks, so only the local log file is
     * written and the flagged warning logged; Discord, callback and AI calls are skipped.
     */
    public void report(SessionData session, String playerName, RiskAssessment assessment) {
        // Write the file-based log synchronously (fast, local I/O)
        LogWriter logWriter = new LogWriter(plugin, session);
        logWriter.writeLog();

        if (assessment == null) {
            // Scoring failed (already logged); nothing to notify about.
            return;
        }

        if (!plugin.isEnabled()) {
            if (isFlagged(assessment)) {
                logFlagged(playerName, assessment);
            }
            return;
        }

        // Run notifications off the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (isFlagged(assessment)) {
                    // Flagged path — optionally call Gemini first
                    String aiVerdict = "AI summary disabled.";
                    if (plugin.getPluginConfig().isAiEnabled()) {
                        String activityLogText = buildActivityLogText(session);
                        aiVerdict = plugin.getGeminiClient().requestVerdict(playerName, activityLogText, assessment);
                    }

                    logFlagged(playerName, assessment);

                    plugin.getWebhookUtil().sendFlaggedAlert(playerName, session, assessment, aiVerdict);

                } else {
                    // Clean path — quiet one-liner
                    plugin.getWebhookUtil().sendCleanSummary(playerName, session, assessment);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("[DoubleLife] Error during session reporting for " + playerName + ": " + e.getMessage());
            }
        });
    }

    private boolean isFlagged(RiskAssessment assessment) {
        RiskLevel threshold = RiskLevel.fromString(plugin.getPluginConfig().getRiskThreshold());
        return assessment.getLevel().isAtLeast(threshold);
    }

    private void logFlagged(String playerName, RiskAssessment assessment) {
        plugin.getLogger().warning("[DoubleLife] Suspicious session detected for " + playerName
                + " — Risk: " + assessment.getLevel().getDisplayName()
                + " (score " + assessment.getScore() + ")");
    }

    private String buildActivityLogText(SessionData session) {
        LogWriter lw = new LogWriter(plugin, session);
        // formatLogForDiscord gives a compact but readable activity summary
        return lw.formatLogForDiscord(3000);
    }
}

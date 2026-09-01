package com.s2s.demo

import com.s2s.agent.skill.Skill
import com.s2s.agent.skill.SkillMetadata
import com.s2s.agent.skill.SkillRegistry

/**
 * The host's hand-authored skills.
 *
 * Distinct from [com.s2s.agent.skill.registerToolSkills], which derives one
 * skill per tool from that tool's own description and carries no instructions.
 * That covers "which tools are even relevant to this request". It cannot cover
 * how to CONDUCT the work, because a `ToolDefinition` describes one tool in
 * isolation and has nowhere to say "check whether you already know this before
 * storing it again".
 *
 * Rules for anything added here, learned from the audit that found the skill
 * mechanism built and empty:
 *
 * 1. **Instructions must be something no tool description can state.** If the
 *    text would fit in the tool's own `description`, put it there instead —
 *    the tool prompt section already reaches the model, so duplicating it here
 *    spends prompt tokens twice for the same words.
 * 2. **`requiredTools` must name tools that actually exist**, or the skill
 *    narrows the catalogue to nothing and the model is left mute for that
 *    request. The names below match `MemoryTools` and `CalculatorTool`.
 * 3. **Selection is substring matching** over name + description (see
 *    [SkillRegistry.findRelevant]), so the description has to contain the words
 *    a user would actually say. A skill described in vocabulary nobody speaks
 *    never activates, which is silent — it looks exactly like a model choosing
 *    not to use a tool.
 *
 * These are deliberately few. A skill that never matches, or matches and adds
 * nothing, is prompt bloat wearing the costume of configuration.
 */
fun SkillRegistry.registerJarvisSkills() {
    register(
        Skill(
            metadata = SkillMetadata(
                id = "jarvis:memory",
                name = "memory",
                // The words a user actually says when this matters: "remember
                // that", "what did I tell you", "forget", "you said".
                description = "Remember, recall or forget what the user told you earlier, " +
                    "including facts about them, their name, and their preferences.",
                requiredTools = setOf("remember", "recall"),
            ),
            // Conduct across BOTH memory tools plus the injected context — none
            // of which either tool's own description can address, because
            // neither tool knows the other exists.
            instructions = "Before storing something with remember, check whether the context you were already " +
                "given contains it; do not store the same fact twice. Store the fact itself in plain words, " +
                "not the sentence the user said it in. When you use recall and it finds nothing, say plainly " +
                "that you do not have it rather than guessing. After storing something, confirm it in one " +
                "short sentence and continue the conversation — do not read the stored text back verbatim.",
        ),
    )

    register(
        Skill(
            metadata = SkillMetadata(
                id = "jarvis:identity",
                name = "identity",
                // Vocabulary a user actually uses when changing this: "call
                // me", "your name is", "call you", "who are you", "speak".
                description = "Change your own name or behaviour, or record the user's name and how they " +
                    "want you to answer them.",
                requiredTools = setOf("set_identity", "set_user_profile"),
            ),
            // The distinction the two tool descriptions cannot enforce between
            // them: which tool a given sentence belongs to. "Call me Sam" and
            // "your name is Sam" differ by one word and write to different
            // stores, and getting it wrong renames the wrong party.
            instructions = "A name the user gives themselves goes to set_user_profile; a name they give YOU " +
                "goes to set_identity. If it is genuinely unclear which they meant, ask before writing. " +
                "Change only what they mentioned and leave the rest alone. Acknowledge in one short " +
                "sentence and carry on.",
        ),
    )

    register(
        Skill(
            metadata = SkillMetadata(
                id = "jarvis:arithmetic",
                name = "calculate",
                description = "Work out a sum, total, percentage or arithmetic result the user asks for.",
                requiredTools = setOf("calculate"),
            ),
            // The failure this exists to prevent was observed in testing: the
            // model answers arithmetic from its own head, confidently and
            // sometimes wrongly, while a correct tool sits unused. And a spoken
            // answer needs different formatting than a written one.
            instructions = "Use the calculate tool for any arithmetic rather than working it out yourself, " +
                "even when the sum looks easy. Report the result as a spoken number in a short sentence, " +
                "without restating the expression.",
        ),
    )
}

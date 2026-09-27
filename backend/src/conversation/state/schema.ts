import { z } from "zod";

export const Phase = z.enum(["intro", "experience", "deep_dive", "wrap_up"]);
export type Phase = z.infer<typeof Phase>;

export const Topic = z.object({
  id: z.string(),
  label: z.string().min(1),
  covered: z.boolean(),
});
export type Topic = z.infer<typeof Topic>;

export const ConversationState = z.object({
  phase: Phase,
  topics: z.array(Topic),
  currentTopicId: z.string().nullable(),
  questionCount: z.number().int().nonnegative(),
});
export type ConversationState = z.infer<typeof ConversationState>;

export const StateProposal = z
  .object({
    phase: Phase.optional(),
    newTopics: z
      .array(z.object({ label: z.string().min(1) }))
      .optional(),
    coveredTopicIds: z.array(z.string()).optional(),
    currentTopicId: z.string().nullable().optional(),
    relabelTopics: z
      .array(z.object({ id: z.string(), label: z.string().min(1) }))
      .optional(),
  })
  .strict();
export type StateProposal = z.infer<typeof StateProposal>;

export const INITIAL_STATE: ConversationState = {
  phase: "intro",
  topics: [],
  currentTopicId: null,
  questionCount: 0,
};

export const MAX_QUESTIONS = 8;

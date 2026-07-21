# Problem-First Recall Prompt Bundle

This directory is a complete prompt bundle for one experiment. It mirrors the
runtime prompt layout so Stage 0, Stage 1, and technique prompts can vary
together without editing the default prompts in `backend/prompts/`.

Files:
- `stage0_soft_prefilter.md`: recall-oriented Stage 0 prefilter. It removes only clearly unsupported techniques and leaves plausible choices for Stage 1.
- `stage1_system_instruction.md`: longer problem-first Stage 1 prompt with PhotoFramer action semantics and camera/framing-only image prompt constraints.
- `techniques/*.md`: recall-oriented technique task prompts used with this experiment.

Default runtime files live in:
- `backend/prompts/default/stage0_soft_prefilter.md`
- `backend/prompts/default/stage1_system_instruction.md`
- `backend/prompts/default/techniques/*.md`

To test this bundle, switch the prompt set:

```bash
export PROMPT_SET=problem_first_recall
```

The runtime will derive Stage 0, Stage 1, and technique prompt paths from this
directory automatically.

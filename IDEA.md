AIEnglish-Mobile is the Android/Shipathon client for Project English, an AI-powered professional English interview practice product.

Repository structure:
- android/ — Android application
- backend/ — standalone backend snapshot used by this Android/Shipathon project

Architecture and source-of-truth:
- The authoritative backend and product behavior are maintained in the separate `project-aienglish-interview` repository.
- The `backend/` directory in this repository is only a synchronized snapshot for the Android/Shipathon project.
- Do not independently redesign backend semantics in the snapshot.
- When backend behavior needs to change, verify the authoritative implementation and contract first.

Product:
- The app helps technology students practice realistic professional English interviews based on their own experience.
- The core learning loop is: answer → qualitative feedback → targeted improvement → retry.
- Feedback is qualitative and actionable; do not introduce numeric scores, grades, percentages, or rankings unless explicitly required by an existing contract.
- Targeted practice should address meaningful weaknesses grounded in the user's actual answer and experience.
- Do not invent user responsibilities, technologies, metrics, outcomes, or experience details.

Development principles:
- Preserve the existing architecture and established patterns.
- Prefer reuse over introducing new abstractions.
- Inspect existing code, tests, and history before making architectural decisions.
- Do not modify unrelated features or pre-existing work.
- Keep Android and backend responsibilities clearly separated.
- Prefer deterministic tests and API injection for behavioral verification.
- Use real-device testing only when it provides evidence that cannot reasonably be obtained through automated tests.

Git safety:
- Inspect `git status` before making changes.
- Preserve all pre-existing working-tree changes.
- Never use destructive commands to clean unrelated work.
- Never use `git reset --hard`, `git clean -fd`, or destructive restore/checkout operations unless explicitly instructed.
- Do not commit unless explicitly requested.
- Do not push unless explicitly requested.
- Use atomic Conventional Commits when committing.
- Commit messages should describe the actual technical change, not internal milestone names.

Validation:
- Run focused tests for the affected area.
- Run relevant regression tests.
- Build the application when appropriate.
- Report concrete evidence and distinguish verified behavior from assumptions.

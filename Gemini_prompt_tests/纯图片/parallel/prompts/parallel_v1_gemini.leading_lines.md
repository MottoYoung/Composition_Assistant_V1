# Task Request
The user has uploaded an image. Please apply the **"Leading Lines"** technique.
1.  **Technique Goal**: Find natural lines (roads, fences, rivers, edges) and align the shot so they point towards the subject.
2.  **Technique Name for JSON**: `leading_lines`
3.  **Analysis**: Crucial step - Check if there are ACTUAL lines in the scene. If no lines exist, set `is_applicable` to `false`.

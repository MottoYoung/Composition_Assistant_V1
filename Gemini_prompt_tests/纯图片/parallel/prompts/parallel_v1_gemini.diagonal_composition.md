# Task Request
The user has uploaded an image. Please apply the **"Diagonal Composition"** technique.
1.  **Technique Goal**: Use **EXISTING** linear elements (fences, roads, architecture edges) to guide the eye diagonally.
2.  **Technique Name for JSON**: `diagonal_composition`
3.  **Strict Analysis Constraints**:
* **DO NOT simply tilt the camera** (Dutch Angle) to create a fake diagonal if the subject is vertical (like a standing person or a building). This looks unprofessional.
* Only set `is_applicable` to `true` if there are **actual physical lines** in the scene that can be aligned diagonally through perspective change.
* If the scene is static and vertical/horizontal dominant, set `is_applicable` to `false`.

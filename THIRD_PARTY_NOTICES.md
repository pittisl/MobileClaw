# Third-party notices

The MIT license in this repository applies to the project code. It does not
replace the licenses of bundled assets, downloaded models or dependencies.

## IBM Plex fonts

The five IBM Plex Sans and IBM Plex Mono font files in `app/src/main/res/font/`
are copyright IBM Corp. and are distributed under the SIL Open Font License 1.1.
The complete license and reserved font name are retained in
[IBM-Plex-OFL.txt](app/src/main/assets/licenses/IBM-Plex-OFL.txt), sourced from
[IBM's Plex repository](https://github.com/IBM/plex/blob/master/LICENSE.txt).

## Search and demonstration images

The test images have their sources, authors and licenses in the
[search-fixture notice](app/src/androidTest/assets/search-fixtures/README.md).
The demonstration photo manifest preserves individual attribution and license
links in [PHOTOS_ATTRIBUTION.json](tools/demo_preload/PHOTOS_ATTRIBUTION.json).
Those image licenses continue to apply to copies and adaptations.

## Optional MobileCLIP model

Model weights are downloaded separately by `tools/fetch_clip_model.sh` from
[Xenova's MobileCLIP-S0 ONNX export](https://huggingface.co/Xenova/mobileclip_s0).
The script downloads the model's license alongside the weights. Consult that
license before using or redistributing the model.

## Build and runtime dependencies

Gradle, Android libraries and other dependencies retain their respective
licenses. Their coordinates and versions are recorded in the Gradle build files
and `gradle/libs.versions.toml`. The Gradle wrapper retains its upstream license
notices in the wrapper scripts.

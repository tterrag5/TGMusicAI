{
  description = "TGMusicAI Android Development Environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "34.0.0" "35.0.0" ];
          platformVersions = [ "34" "35" ];
          abiVersions = [ "x86_64" "arm64-v8a" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
        };

        androidSdk = androidComposition.androidsdk;
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.jdk17
            pkgs.android-tools
            pkgs.gradle
            androidSdk
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17.home}"
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/tools:$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH"
            unset ANDROID_PREFS_ROOT

            echo "=========================================="
            echo " TGMusicAI Android Development Shell"
            echo " JAVA_HOME=$JAVA_HOME"
            echo " ANDROID_HOME=$ANDROID_HOME"
            echo "=========================================="
          '';
        };
      }
    );
}

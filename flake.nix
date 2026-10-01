{
  description = "h34s Scala.js and Nuxt development toolchain";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      toolchainFor = system:
        let
          pkgs = import nixpkgs { inherit system; };
          nodejs = pkgs.nodejs_24;
          pnpm = pkgs.pnpm_10.override { nodejs-slim = nodejs; };
          jdk = pkgs.jdk17;
          package = builtins.fromJSON (builtins.readFile ./package.json);
        in
        assert package.packageManager == "pnpm@${pnpm.version}";
        {
          inherit pkgs nodejs pnpm jdk;
          sbt = pkgs.sbt.override { jre = jdk; };
        };
    in
    {
      devShells = forAllSystems (system:
        let tools = toolchainFor system;
        in {
          default = tools.pkgs.mkShell {
            packages = [
              tools.nodejs
              tools.pnpm
              tools.jdk
              tools.sbt
              tools.pkgs.git
              tools.pkgs.python3
              tools.pkgs.pkg-config
            ];
            JAVA_HOME = tools.jdk.home;
            NUXT_TELEMETRY_DISABLED = "1";
          };
        });

      checks = forAllSystems (system:
        let tools = toolchainFor system;
        in {
          toolchain = tools.pkgs.runCommand "h34s-toolchain-check" {
            nativeBuildInputs = [ tools.nodejs tools.pnpm tools.jdk ];
          } ''
            export HOME="$TMPDIR"
            node --version
            test "$(pnpm --version)" = "${tools.pnpm.version}"
            java -version
            touch "$out"
          '';
        });
    };
}

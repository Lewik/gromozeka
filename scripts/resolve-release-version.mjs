import { appendFileSync, readFileSync } from "node:fs";

const semverPattern =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?$/;

function parseSemver(value) {
  const match = semverPattern.exec(value);
  if (!match) {
    throw new Error(`Invalid release version: ${value}`);
  }

  return {
    raw: value,
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4] ? match[4].split(".") : [],
  };
}

function compareIdentifiers(left, right) {
  if (left === right) return 0;

  const leftNumeric = /^(0|[1-9]\d*)$/.test(left);
  const rightNumeric = /^(0|[1-9]\d*)$/.test(right);

  if (leftNumeric && rightNumeric) {
    return Number(left) - Number(right);
  }
  if (leftNumeric) return -1;
  if (rightNumeric) return 1;

  return left < right ? -1 : 1;
}

function compareSemver(left, right) {
  for (const key of ["major", "minor", "patch"]) {
    if (left[key] !== right[key]) {
      return left[key] - right[key];
    }
  }

  if (left.prerelease.length === 0 && right.prerelease.length === 0) return 0;
  if (left.prerelease.length === 0) return 1;
  if (right.prerelease.length === 0) return -1;

  const maxLength = Math.max(left.prerelease.length, right.prerelease.length);
  for (let index = 0; index < maxLength; index += 1) {
    const leftIdentifier = left.prerelease[index];
    const rightIdentifier = right.prerelease[index];

    if (leftIdentifier === undefined) return -1;
    if (rightIdentifier === undefined) return 1;

    const result = compareIdentifiers(leftIdentifier, rightIdentifier);
    if (result !== 0) return result;
  }

  return 0;
}

function maxVersion(versions) {
  return versions.reduce((max, version) => {
    if (!max || compareSemver(version, max) > 0) {
      return version;
    }
    return max;
  }, undefined);
}

function normalizeRemoteTag(value) {
  return value.trim().replace(/^refs\/tags\/v/, "").replace(/^v/, "").replace(/\^\{\}$/, "");
}

function readRemoteVersions() {
  const input = readFileSync(0, "utf8");
  const uniqueVersions = new Set();

  for (const line of input.split(/\r?\n/)) {
    const version = normalizeRemoteTag(line);
    if (version && semverPattern.test(version)) {
      uniqueVersions.add(version);
    }
  }

  return [...uniqueVersions].map(parseSemver);
}

function generatedVersion(latestStable, bump) {
  if (!["major", "minor", "patch"].includes(bump)) {
    throw new Error(`Invalid release bump: ${bump}`);
  }

  if (!latestStable) {
    if (bump === "major") return "1.0.0";
    if (bump === "minor") return "0.1.0";
    return "0.0.1";
  }

  if (bump === "major") return `${latestStable.major + 1}.0.0`;
  if (bump === "minor") return `${latestStable.major}.${latestStable.minor + 1}.0`;
  return `${latestStable.major}.${latestStable.minor}.${latestStable.patch + 1}`;
}

function writeOutput(values) {
  const lines = Object.entries(values).map(([key, value]) => `${key}=${value}`);
  const output = `${lines.join("\n")}\n`;

  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, output);
  } else {
    process.stdout.write(output);
  }
}

function resolveReleaseVersion() {
  const eventName = process.env.GITHUB_EVENT_NAME ?? "";
  const remoteVersions = readRemoteVersions();
  const stableRemoteVersions = remoteVersions.filter((version) => version.prerelease.length === 0);

  const publish = eventName === "push" ? true : process.env.INPUT_PUBLISH_RELEASE === "true";
  const deploy = eventName === "push" ? true : process.env.INPUT_DEPLOY_AWS === "true";
  const bump = process.env.INPUT_BUMP || "patch";
  const requestedVersion =
    eventName === "push"
      ? (process.env.GITHUB_REF_NAME ?? "").replace(/^v/, "")
      : (process.env.INPUT_VERSION ?? "").trim();

  if (deploy && !publish) {
    throw new Error("deploy_aws=true requires publish_release=true");
  }

  const version = requestedVersion || generatedVersion(maxVersion(stableRemoteVersions), bump);
  const parsedVersion = parseSemver(version);

  if (publish && version === "0.0.0-dev") {
    throw new Error("0.0.0-dev is reserved for local builds and cannot be published");
  }

  const exactRemoteVersionExists = remoteVersions.some((remoteVersion) => remoteVersion.raw === version);
  if (publish && eventName !== "push" && exactRemoteVersionExists) {
    throw new Error(`Release version ${version} already exists as a remote release tag`);
  }

  const latestPrevious = maxVersion(remoteVersions.filter((remoteVersion) => remoteVersion.raw !== version));
  if (publish && latestPrevious && compareSemver(parsedVersion, latestPrevious) <= 0) {
    throw new Error(
      `Release version ${version} must be greater than latest remote release tag ${latestPrevious.raw}`,
    );
  }

  writeOutput({
    version,
    tag: `v${version}`,
    publish,
    prerelease: parsedVersion.prerelease.length > 0,
    deploy,
    latest_previous: latestPrevious?.raw ?? "",
  });
}

try {
  resolveReleaseVersion();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exit(2);
}

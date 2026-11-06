// The two outside systems the journey would otherwise need, faked at the network boundary so aegis-core's real NVD
// connector and real Jira adapter run: the NVD CVE API (one vulnerability in a package only this journey uses) and
// Atlassian's OAuth + Jira REST APIs (consent is granted at once, issues are kept in memory).
import { createServer } from "node:http";

const PORT = Number(process.env.FAKE_UPSTREAMS_PORT ?? 9099);
const CLOUD_ID = "11111111-2222-4333-8444-555555555555";
const PROJECT = { id: "10000", key: "AEG", name: "Aegis journey" };
const CVE_ID = "CVE-2026-90001";

const cve = {
  id: CVE_ID,
  published: "2026-01-01T00:00:00.000",
  lastModified: "2026-01-01T00:00:00.000",
  vulnStatus: "Analyzed",
  descriptions: [{ lang: "en", value: "Remote code execution in journey-lib before 1.2.4." }],
  metrics: {
    cvssMetricV31: [{
      source: "nvd@nist.gov", type: "Primary", baseSeverity: "CRITICAL",
      cvssData: { version: "3.1", vectorString: "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", baseScore: 9.8, baseSeverity: "CRITICAL" },
    }],
  },
  configurations: [{
    nodes: [{
      operator: "OR", negate: false,
      cpeMatch: [{ vulnerable: true, criteria: "cpe:2.3:a:aegisjourney:journey-lib:*:*:*:*:*:*:*:*", matchCriteriaId: "00000000-0000-4000-8000-000000000001", versionEndExcluding: "1.2.4" }],
    }],
  }],
  references: [{ url: "https://example.org/advisories/journey-lib", tags: ["Vendor Advisory"] }],
};

const issues = [];

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

async function bodyOf(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : null;
}

const jira = `/ex/jira/${CLOUD_ID}/rest/api/3`;

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const route = `${req.method} ${url.pathname}`;

  if (route === "GET /health") return json(res, 200, { status: "UP" });

  if (route === "GET /nvd") {
    const first = Number(url.searchParams.get("startIndex") ?? 0) === 0;
    return json(res, 200, { totalResults: 1, vulnerabilities: first ? [{ cve }] : [] });
  }

  // The admin's browser arrives here from Connect Jira: consent is granted at once.
  if (route === "GET /authorize") {
    const back = new URL(url.searchParams.get("redirect_uri"));
    back.searchParams.set("code", "journey-consent");
    back.searchParams.set("state", url.searchParams.get("state"));
    res.writeHead(302, { location: back.toString() });
    return res.end();
  }
  if (route === "POST /oauth/token") {
    return json(res, 200, { access_token: "journey-access", refresh_token: "journey-refresh", expires_in: 3600 });
  }
  if (route === "GET /oauth/token/accessible-resources") {
    return json(res, 200, [{ id: CLOUD_ID, name: "Journey", url: "https://journey.atlassian.net", scopes: ["read:jira-work", "write:jira-work"] }]);
  }
  if (route === `GET ${jira}/project/search`) return json(res, 200, { values: [PROJECT] });
  if (route === `GET ${jira}/project/${PROJECT.key}`) {
    return json(res, 200, { ...PROJECT, issueTypes: [{ id: "1", name: "Task", subtask: false }, { id: "2", name: "Sub-task", subtask: true }] });
  }
  if (route === `POST ${jira}/issue`) {
    const { fields } = await bodyOf(req);
    const key = `${PROJECT.key}-${issues.length + 1}`;
    issues.push({ key, fields });
    return json(res, 201, { id: String(issues.length), key });
  }

  // For the journey's assertions: what actually reached "Jira".
  if (route === "GET /__test/issues") return json(res, 200, issues);

  return json(res, 404, { error: `No fake for ${route}` });
});

server.listen(PORT, "127.0.0.1", () => console.log(`fake upstreams on http://127.0.0.1:${PORT}`));

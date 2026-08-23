import http from "node:http";

const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "";
const PORT = Number(process.env.PORT || 8787);

if (!OPENAI_API_KEY) throw new Error("OPENAI_API_KEY manquant");
if (!OPENAI_MODEL) throw new Error("OPENAI_MODEL manquant");

const actionTypes = [
  "set_lens", "set_ring", "set_alpha", "set_transparency", "set_translucency", "set_rotation", "set_light_angle",
  "set_edge_width", "set_edge_alpha", "set_edge_contrast", "set_edge_softness",
  "set_radial_edges", "set_circular_edges", "add_entry", "add_pause", "add_exit",
  "add_frame", "add_background", "duplicate", "delete", "bring_forward",
  "send_backward", "toggle_lock"
];

const tool = {
  type: "function",
  name: "apply_designer_actions",
  description: "Translate the user's French Diamond Designer request into safe visual editing actions.",
  strict: true,
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      reply: { type: "string" },
      actions: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          properties: {
            type: { type: "string", enum: actionTypes },
            value: { type: "number" },
            ring: { type: "integer", minimum: 0, maximum: 3 }
          },
          required: ["type", "value", "ring"]
        }
      }
    },
    required: ["reply", "actions"]
  }
};

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") return json(res, 200, { ok: true });
  if (req.method !== "POST" || req.url !== "/designer") return json(res, 404, { error: "not_found" });

  try {
    let raw = "";
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 200_000) throw new Error("payload_too_large");
    }
    const input = JSON.parse(raw || "{}");
    if (input.protocol !== "diamond_designer_v1") return json(res, 400, { error: "bad_protocol" });

    const userText = [
      "User request:", input.message || "",
      "\nCurrent Diamond Designer state:", input.designer_state || ""
    ].join("\n");

    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "authorization": `Bearer ${OPENAI_API_KEY}`,
        "content-type": "application/json"
      },
      body: JSON.stringify({
        model: OPENAI_MODEL,
        instructions: [
          "You are the in-app Diamond Designer assistant.",
          "Understand French naturally and modify only the selected design object through the provided function.",
          "Never invent unsupported actions. Keep values conservative unless the user explicitly asks for a strong change.",
          "Ranges: lens/alpha/transparency/translucency/edge_alpha/edge_contrast/edge_softness 0..1; ring gains 0.2..1.8; edge width 0.1..12; radial/circular edges 0..2; rotation/light angle degrees.",
          "Transparency means the whole object fades away. Translucency means diamond material lets the background show through while facets remain readable.",
          "For actions that do not use value or ring, send value=0 and ring=0. For set_ring, ring must be 1, 2, or 3."
        ].join(" "),
        input: userText,
        tools: [tool],
        tool_choice: { type: "function", name: "apply_designer_actions" },
        parallel_tool_calls: false
      })
    });

    const data = await response.json();
    if (!response.ok) return json(res, response.status, { error: "openai_error", detail: data?.error?.message || "unknown" });

    const call = (data.output || []).find(x => x.type === "function_call" && x.name === "apply_designer_actions");
    if (!call?.arguments) return json(res, 502, { error: "missing_function_call" });
    const args = JSON.parse(call.arguments);
    return json(res, 200, { reply: args.reply || "Modification appliquée.", actions: args.actions || [] });
  } catch (error) {
    return json(res, 500, { error: "proxy_error", detail: String(error?.message || error) });
  }
});

server.listen(PORT, "0.0.0.0", () => console.log(`Diamond Designer AI proxy listening on ${PORT}`));

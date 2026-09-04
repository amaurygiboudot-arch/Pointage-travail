"use strict";

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function findSiret(recherche) {
  if (!isPlainObject(recherche) || !Array.isArray(recherche.champs)) return null;
  for (const champ of recherche.champs) {
    if (!isPlainObject(champ) || !Array.isArray(champ.criteres)) continue;
    for (const critere of champ.criteres) {
      const digits = String(critere?.valeur ?? "").replace(/\D/g, "");
      if (digits.length === 14) return digits;
    }
  }
  return null;
}

function normalizeLegifranceBody(path, body) {
  const safeBody = isPlainObject(body) ? body : {};
  if (path === "/consult/kaliContIdcc") {
    const digits = String(safeBody.id ?? "").replace(/\D/g, "");
    if (!digits || digits.length > 4) return {};
    return { id: String(Number(digits)) };
  }

  if (path === "/list/conventions") {
    const pageNumber = Math.min(20, Math.max(1, Number.parseInt(safeBody.pageNumber, 10) || 1));
    const pageSize = Math.min(100, Math.max(1, Number.parseInt(safeBody.pageSize, 10) || 100));
    const allowedStatuses = new Set(["VIGUEUR", "VIGUEUR_ETEN", "VIGUEUR_NON_ETEN", "VIGUEUR_DIFF"]);
    const legalStatus = Array.isArray(safeBody.legalStatus)
      ? safeBody.legalStatus.filter((value) => allowedStatuses.has(value))
      : [];
    return {
      pageNumber,
      pageSize,
      sort: "DATE_UPDATE",
      ...(legalStatus.length ? { legalStatus } : {}),
      ...(typeof safeBody.searchValue === "string" && safeBody.searchValue.trim()
        ? { searchValue: safeBody.searchValue.trim().slice(0, 120) }
        : {}),
    };
  }

  if (path !== "/search" || safeBody.fond !== "ACCO" || !isPlainObject(safeBody.recherche)) {
    return safeBody;
  }

  const recherche = safeBody.recherche;
  const siret = findSiret(recherche);
  if (!siret) return safeBody;

  const currentFilters = Array.isArray(recherche.filtres) ? recherche.filtres : [];
  const hasSiretFilter = currentFilters.some(
    (filter) => isPlainObject(filter) && filter.facette === "SIRET_RAISON_SOCIALE"
  );
  const filtres = hasSiretFilter
    ? currentFilters
    : [...currentFilters, { valeurs: [siret], facette: "SIRET_RAISON_SOCIALE" }];

  return {
    ...safeBody,
    recherche: {
      ...recherche,
      filtres,
      sort: !recherche.sort || recherche.sort === "PERTINENCE" ? "DATE_DESC" : recherche.sort,
      fromAdvancedRecherche: recherche.fromAdvancedRecherche ?? false,
      secondSort: recherche.secondSort || "ID",
    },
  };
}

function isValidLegifranceBody(path, body) {
  if (!isPlainObject(body)) return false;
  if (path === "/consult/kaliContIdcc") {
    const digits = String(body.id ?? "").replace(/\D/g, "");
    return digits.length >= 1 && digits.length <= 4 && Number(digits) > 0;
  }
  return true;
}

function publicFailureMessage(stage, status, path = "") {
  if (stage === "configuration") {
    return "Configuration PISTE absente côté serveur.";
  }
  if (stage === "oauth") {
    if (status >= 400 && status <= 403) {
      return `Authentification PISTE refusée (OAuth ${status}). Vérifie les identifiants de production.`;
    }
    return `Service OAuth PISTE indisponible${status ? ` (HTTP ${status})` : ""}.`;
  }
  if (stage === "api") {
    if (status === 400) {
      const source = path.includes("kali") || path === "/list/conventions" ? "KALI" : "ACCO";
      return `Requête ${source} refusée par Légifrance (HTTP 400).`;
    }
    if (status === 401 || status === 403) {
      return `Accès à l’API Légifrance refusé (HTTP ${status}). Vérifie l’abonnement PISTE à l’API.`;
    }
    if (status === 429) return "Quota de l’API Légifrance atteint (HTTP 429).";
    return `API Légifrance indisponible${status ? ` (HTTP ${status})` : ""}.`;
  }
  if (stage === "response") return "Réponse Légifrance invalide.";
  if (stage === "timeout") return "PISTE/Légifrance ne répond pas (délai dépassé).";
  return "Connexion à PISTE/Légifrance impossible.";
}

module.exports = { isValidLegifranceBody, normalizeLegifranceBody, publicFailureMessage };

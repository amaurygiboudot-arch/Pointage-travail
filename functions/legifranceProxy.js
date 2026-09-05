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

function boundedInt(value, min, max, fallback) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function normalizedOfficialId(value, prefix) {
  const id = String(value ?? "").trim().toUpperCase();
  return new RegExp(`^${prefix}\\d+$`).test(id) ? id : "";
}

function normalizedIdcc(value) {
  const digits = String(value ?? "").replace(/\D/g, "");
  if (!digits || digits.length > 4 || Number(digits) <= 0) return "";
  return String(Number(digits));
}

function firstBoccIdcc(body) {
  if (Array.isArray(body?.idccs)) {
    for (const raw of body.idccs) {
      const value = normalizedIdcc(raw);
      if (value) return value;
    }
  }
  return normalizedIdcc(body?.idcc);
}

function normalizedBoccInterval(value) {
  const interval = typeof value === "string" ? value.trim().replace(/\s+/g, " ") : "";
  return /^\d{2}\/\d{2}\/\d{4} > \d{2}\/\d{2}\/\d{4}$/.test(interval) ? interval : "";
}

function normalizedBoccFileName(value) {
  const fileName = typeof value === "string" ? value.trim() : "";
  return /^[A-Za-z0-9_.-]{1,160}\.pdf$/i.test(fileName) ? fileName : "";
}

function firstSearchCriterionValue(recherche) {
  if (!isPlainObject(recherche) || !Array.isArray(recherche.champs)) return "";
  for (const champ of recherche.champs) {
    if (!isPlainObject(champ) || !Array.isArray(champ.criteres)) continue;
    for (const critere of champ.criteres) {
      const value = typeof critere?.valeur === "string" ? critere.valeur.trim().replace(/\s+/g, " ") : "";
      if (value) return value;
    }
  }
  return "";
}

function codeDateVersion(recherche) {
  if (!isPlainObject(recherche) || !Array.isArray(recherche.filtres)) return 0;
  const filter = recherche.filtres.find(
    (item) => isPlainObject(item) && item.facette === "DATE_VERSION"
  );
  const date = Number(filter?.singleDate);
  return Number.isFinite(date) && date > 0 ? Math.trunc(date) : 0;
}

function normalizeCodeDateSearch(safeBody) {
  const recherche = isPlainObject(safeBody.recherche) ? safeBody.recherche : {};
  const query = firstSearchCriterionValue(recherche).slice(0, 120);
  const date = codeDateVersion(recherche);
  if (!query || !date) return {};
  return {
    fond: "CODE_DATE",
    recherche: {
      champs: [{
        typeChamp: "ARTICLE",
        criteres: [{ typeRecherche: "UN_DES_MOTS", valeur: query, operateur: "ET" }],
        operateur: "ET",
      }],
      filtres: [
        { facette: "NOM_CODE", valeurs: ["Code du travail"] },
        { facette: "DATE_VERSION", singleDate: date },
      ],
      pageNumber: 1,
      pageSize: boundedInt(recherche.pageSize, 1, 25, 10),
      operateur: "ET",
      sort: "PERTINENCE",
      typePagination: "DEFAUT",
    },
  };
}

function isValidCodeDateSearch(body) {
  if (body.fond !== "CODE_DATE" || !isPlainObject(body.recherche)) return false;
  const query = firstSearchCriterionValue(body.recherche);
  const date = codeDateVersion(body.recherche);
  return query.length > 0 && query.length <= 120 && date > 0;
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

  if (path === "/list/boccsAndTexts") {
    const idcc = firstBoccIdcc(safeBody);
    const intervalPublication = normalizedBoccInterval(safeBody.intervalPublication);
    if (!idcc || !intervalPublication) return {};
    return {
      idcc,
      intervalPublication,
      pageNumber: boundedInt(safeBody.pageNumber, 1, 100, 1),
      pageSize: boundedInt(safeBody.pageSize, 1, 100, 50),
      sortValue: safeBody.sortValue === "BOCC_SORT_ASC" ? "BOCC_SORT_ASC" : "BOCC_SORT_DESC",
    };
  }

  if (path === "/consult/getBoccTextPdfMetadata") {
    const id = normalizedBoccFileName(safeBody.id);
    return id ? { id, forGlobalBocc: false } : {};
  }

  if (path === "/consult/getArticle") {
    const id = normalizedOfficialId(safeBody.id, "LEGIARTI");
    return id ? { id } : {};
  }

  if (path === "/consult/legiPart") {
    const textId = normalizedOfficialId(safeBody.textId, "LEGITEXT");
    const date = Number(safeBody.date);
    return textId && Number.isFinite(date) && date > 0 ? { date: Math.trunc(date), textId } : {};
  }

  if (path === "/consult/lastNJo") {
    return { nbElement: boundedInt(safeBody.nbElement, 1, 100, 5) };
  }

  if (path === "/consult/jorfCont") {
    const id = normalizedOfficialId(safeBody.id, "JORFCONT");
    if (!id) return {};
    return {
      highlightActivated: safeBody.highlightActivated !== false,
      id,
      pageNumber: boundedInt(safeBody.pageNumber, 1, 100, 1),
      pageSize: boundedInt(safeBody.pageSize, 1, 100, 25),
    };
  }

  if (path === "/consult/jorf") {
    const textCid = normalizedOfficialId(safeBody.textCid, "JORFTEXT");
    return textCid ? { textCid } : {};
  }

  if (path === "/search" && safeBody.fond === "CODE_DATE") {
    return normalizeCodeDateSearch(safeBody);
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
  if (path === "/search" && body.fond === "CODE_DATE") {
    return isValidCodeDateSearch(body);
  }
  if (path === "/consult/kaliContIdcc") {
    const digits = String(body.id ?? "").replace(/\D/g, "");
    return digits.length >= 1 && digits.length <= 4 && Number(digits) > 0;
  }
  if (path === "/list/boccsAndTexts") {
    return Boolean(firstBoccIdcc(body)) && Boolean(normalizedBoccInterval(body.intervalPublication));
  }
  if (path === "/consult/getBoccTextPdfMetadata") {
    return Boolean(normalizedBoccFileName(body.id));
  }
  if (path === "/consult/getArticle") {
    return Boolean(normalizedOfficialId(body.id, "LEGIARTI"));
  }
  if (path === "/consult/legiPart") {
    return Boolean(normalizedOfficialId(body.textId, "LEGITEXT")) && Number.isFinite(Number(body.date)) && Number(body.date) > 0;
  }
  if (path === "/consult/lastNJo") {
    const count = Number(body.nbElement);
    return Number.isFinite(count) && count >= 1 && count < 2500;
  }
  if (path === "/consult/jorfCont") {
    return Boolean(normalizedOfficialId(body.id, "JORFCONT"));
  }
  if (path === "/consult/jorf") {
    return Boolean(normalizedOfficialId(body.textCid, "JORFTEXT"));
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
      const lowerPath = path.toLowerCase();
      const source = lowerPath.includes("bocc")
        ? "BOCC"
        : lowerPath.includes("kali") || path === "/list/conventions"
          ? "KALI"
          : lowerPath.includes("jorf") || lowerPath.includes("lastnjo")
            ? "JORF"
            : lowerPath.includes("legi") || lowerPath.includes("getarticle")
              ? "LEGI"
              : "ACCO";
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

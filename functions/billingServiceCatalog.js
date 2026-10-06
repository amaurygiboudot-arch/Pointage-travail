"use strict";

// Prices are approved targets, never a replacement for Google Play's localized price.
// Availability is server-owned and cannot be changed by request fields or owner exemption.
const SERVICES = Object.freeze({
  horatrack_analysis: Object.freeze({ serviceId: "payslip_analysis", title: "Analyse d'un bulletin", targetPriceEuroCents: 499, pdfIncluded: true, availability: "AVAILABLE" }),
  horatrack_payslip_comparison: Object.freeze({ serviceId: "payslip_comparison", title: "Comparaison pointage / bulletin", targetPriceEuroCents: 699, pdfIncluded: true, availability: "AVAILABLE" }),
  horatrack_annual_review: Object.freeze({ serviceId: "annual_review", title: "Bilan annuel", targetPriceEuroCents: 999, pdfIncluded: true, availability: "AVAILABLE" }),
  horatrack_claim_dossier: Object.freeze({ serviceId: "claim_dossier", title: "Dossier de réclamation", targetPriceEuroCents: 1499, pdfIncluded: true, availability: "AVAILABLE" }),
});
class ServiceOrderError extends Error { constructor(code, message) { super(message); this.code = code; } }
function validateServiceOrder(data) {
  if (!data || !Object.hasOwn(SERVICES, data.productId) ||
      typeof data.requestId !== "string" || !/^[A-Za-z0-9_-]{16,128}$/.test(data.requestId) ||
      typeof data.inputSha256 !== "string" || !/^[a-f0-9]{64}$/.test(data.inputSha256)) {
    throw new ServiceOrderError("invalid-argument", "Service, identifiant idempotent et empreinte des données requis.");
  }
  const service = SERVICES[data.productId];
  if (service.availability !== "AVAILABLE") throw new ServiceOrderError("failed-precondition", "Ce service n'est pas encore disponible : aucun achat ni crédit ne doit être consommé.");
  return { productId: data.productId, serviceId: service.serviceId, requestId: data.requestId, inputSha256: data.inputSha256 };
}
module.exports = { SERVICES, ServiceOrderError, validateServiceOrder };

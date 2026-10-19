interface BrandProps {
  compact?: boolean;
  inverse?: boolean;
}

export function Brand({ compact = false, inverse = false }: BrandProps) {
  return (
    <div className={`brand${compact ? " brand--compact" : ""}${inverse ? " brand--inverse" : ""}`}>
      <img src="/tacresq-app-icon-192.png" className="brand__mark" alt="" />
      <span className="brand__wordmark">
        <strong>TACResQ</strong>
        {!compact && <small>RESPONSE OPERATIONS</small>}
      </span>
    </div>
  );
}

interface BrandProps {
  compact?: boolean;
  inverse?: boolean;
}

export function Brand({ compact = false, inverse = false }: BrandProps) {
  return (
    <div className={`brand${compact ? " brand--compact" : ""}${inverse ? " brand--inverse" : ""}`}>
      <img src="/tacru-mark.svg" className="brand__mark" alt="" />
      <span className="brand__wordmark">
        <strong>TACRU</strong>
        {!compact && <small>RESPONSE OPERATIONS</small>}
      </span>
    </div>
  );
}

# ADR-0004: Geofencing is advisory-with-evidence; the hard-gate list is closed
Status: Accepted (locked)
Out-of-radius check-in always proceeds with reason + evidence + AM review flag, judged against
effective_radius = stored radius + reported GPS accuracy. The only hard gates are listed in
contracts/c6-permissions/matrix.yaml#hard_gates. Additions require a product-owner RFC.
Morning attendance NEVER gates on location (logged only).

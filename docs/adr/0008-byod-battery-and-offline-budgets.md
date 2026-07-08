# ADR-0008: BYOD; battery and offline budgets are release gates
Status: Accepted (locked)
DCs use personal Rs.8-15k Android phones. Budgets: ≤15% battery per 10-hour day on named
reference devices (Redmi/Vivo Y/Samsung M, 4GB); 5 days of full offline activity stored
on-device; photos 150–300KB (up to 500KB for QR/device categories); capture-to-saved <2s;
cold-start crash-resume <3s. Sampling policy bends to the battery budget, never vice versa.

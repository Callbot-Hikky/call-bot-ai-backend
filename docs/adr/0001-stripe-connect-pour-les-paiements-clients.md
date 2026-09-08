---
status: accepted
---

# Stripe Connect pour les paiements des convives

Les réservations payantes font transiter l'argent des convives vers les restaurateurs.
Nous encaissons via **Stripe Connect en destination charges** : le paiement est créé sur
le compte plateforme d'Alloquence, qui prélève sa commission, et Stripe reverse au compte
connecté de l'organisation. Alloquence pilote le flux et le calendrier des reversements
sans jamais détenir les fonds pour compte de tiers.

## Options écartées

**Encaisser sur un compte Stripe Alloquence classique, puis virer aux restaurateurs.**
C'était l'intention initiale, et c'est ce que le montage retenu reproduit fonctionnellement.
Écarté pour deux raisons : cela constitue une activité d'encaissement pour compte de tiers,
soumise à agrément ACPR ou exemption ; et les conditions d'utilisation de Stripe l'interdisent
explicitement pour un compte standard, avec un risque de fermeture du compte — donc d'arrêt
total du service, abonnements compris.

## Conséquences

**L'abstraction `PaymentGateway` ne couvre pas ce flux.** Le port existant et son adaptateur
`gateway/stripe/` ont été écrits pour être portables : un `PAYMENT_PROVIDER` bascule
l'implémentation, et le README documente la recette. Connect ne se prête pas à cet exercice —
onboarding Express, account links, destination charges et `application_fee_amount` sont
spécifiques à Stripe. Les paiements convives sont donc délibérément liés à Stripe, là où
l'abonnement restaurateur reste théoriquement portable. Un lecteur qui découvrira cette
asymétrie doit savoir qu'elle est assumée, pas accidentelle.

**Les webhooks sont deux flux distincts.** Les événements de comptes connectés arrivent avec
un secret de signature différent de celui de l'abonnement. Il faut un second endpoint et un
second secret, pas une branche supplémentaire dans le parser existant, qui ne laisse aujourd'hui
passer que `checkout.session.completed`.

**Alloquence porte la responsabilité des litiges.** En destination charges, c'est la plateforme
qui supporte les contestations bancaires et leurs frais, y compris sur des prélèvements décidés
par un restaurateur. Cela impose de suivre le taux de litige par organisation et de pouvoir
désactiver le mode pour un restaurateur qui en génère trop.

**Le calendrier de reversement se cale sur la date du service, pas sur celle du paiement.**
Les frais de réservation sont remboursables jusqu'à un délai précédant le service : les fonds
doivent rester disponibles jusque-là.

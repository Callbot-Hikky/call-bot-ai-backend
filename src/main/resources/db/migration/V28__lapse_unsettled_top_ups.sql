-- Fin de vie d'une demande de complément : réglée, caduque, ou dans l'impasse.
--
-- Le ticket 09 ouvrait la demande ; il ne la fermait jamais. Un encaissement en attente
-- restait en attente indéfiniment, et l'index partiel qui n'en tolère qu'un seul par
-- réservation interdisait de fait toute nouvelle hausse après un abandon.
--
-- Trois fins possibles, dont deux existent déjà dans le registre :
--
--   * réglée et une table est libre → `paid`, les couverts passent à M ;
--   * réglée mais plus aucune table → `refunded`, le complément revient intégralement ;
--   * jamais réglée → il faut un mot pour ça, et c'est celui qu'on ajoute.
--
-- `lapsed` : la demande a perdu son objet sans qu'un centime soit entré. Les 30 minutes
-- se sont écoulées, la réservation a été annulée dessous, ou la tablée a été revue à la
-- baisse et le montant demandé ne correspondait plus à rien. Un seul mot pour les trois :
-- du point de vue du registre, ce qui compte est qu'aucun argent n'est entré et qu'aucun
-- n'entrera. La cause est dans le journal, pas dans le statut.
--
-- Distinct de `refunded`, qui suppose un mouvement dans les deux sens : confondre les
-- deux ferait apparaître des remboursements qui n'ont jamais eu lieu.

ALTER TABLE reservation_charges
    DROP CONSTRAINT chk_reservation_charges_status;

ALTER TABLE reservation_charges
    ADD CONSTRAINT chk_reservation_charges_status
        CHECK (status IN ('pending', 'paid', 'refunded', 'lapsed'));

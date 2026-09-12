-- Un restaurant peut avoir plusieurs cartes en PDF (plats, vins, desserts...),
-- ordonnees par position comme les images. La publication reste a un seul mode.
DROP INDEX IF EXISTS uq_restaurant_menu_files_single_pdf;

-- Un seul PDF par restaurant, garanti par la base (deux uploads simultanes ne
-- peuvent pas laisser deux lignes 'pdf').
CREATE UNIQUE INDEX uq_restaurant_menu_files_single_pdf
    ON restaurant_menu_files (restaurant_id)
    WHERE kind = 'pdf';

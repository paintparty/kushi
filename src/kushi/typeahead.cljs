(ns kushi.typeahead)

;; auto-complete hinting for built-in kushi utility classes
:.sans
:.flex-row-c
:.bgi-cover
:.flex-row-sa
:.flex-col-se
:.debug-grid-16
:.full-width
:.bgi-contain
:.lowercase
:.flex-row-fs
:.fixed-fill
:.flex-col-c
:.content-blank
:.bordered
:.debug-grid-8-solid
:.pill
:.uppercase
:.flex-row-se
:.flex-col-centered
:.absolute
:.absolute-fill
:.debug-grid
:.full-width-kana
:.pointer
:.flex-col-fe
:.flex-row-centered
:.debug-grid-16-solid
:.outlined
:.flex-col-fs
:.flex-row-fe
:.flex-col-sa
:.flex-col-sb
:.fixed
:.absolute-centered
:.italic
:.flex-row-sb
:.capitalize
:.relative
:.oblique

;; ui-specific
:.mini
:.small
:.medium
:.large
:.huge
:.rounded
:.sharp
:.ghosted
:.disabled
:.primary
:.secondary
:.tertiary
:.link
:.thin
:.light
:.normal
:.bold

;; auto-complete for built-in kushi-style shorthand
:ai--b
:ai--c
:ai--e
:ai--fe
:ai--fs
:ai--n
:ai--s
:align-items--baseline
:align-items--center
:align-items--end
:align-items--flex-end
:align-items--flex-start
:align-items--normal
:align-items--start
:b--
:background--
:background-color--
:background-image--
:background-position--bottom
:background-position--center
:background-position--left
:background-position--right
:background-position--top
:background-repeat--no-repeat
:background-repeat--repeat-x
:background-repeat--repeat-y
:background-repeat--round
:background-repeat--space
:background-size--
:bb--
:bc--
:bg--
:bgc--
:bgi--
:bgp--b
:bgp--c
:bgp--l
:bgp--r
:bgp--t
:bgr--nr
:bgr--r
:bgr--rx
:bgr--ry
:bgr--s
:bgs--
:bl--
:border--
:border-bottom--
:border-color--
:border-left--
:border-right--
:border-style--dotted
:border-style--groove
:border-style--hidden
:border-style--inset
:border-style--none
:border-style--outset
:border-style--ridge
:border-style--solid
:border-top--
:border-width--
:br--
:bs--d
:bs--g
:bs--h
:bs--i
:bs--n
:bs--o
:bs--r
:bs--s
:bt--
:bw--
:c--
:color--
:d--b
:d--c
:d--f
:d--g
:d--i
:d--ib
:d--if
:d--ig
:d--it
:d--li
:d--t
:d--tc
:d--tcg
:d--tfg
:d--thg
:d--tr
:d--trg
:display--block
:display--contents
:display--flex
:display--grid
:display--inline
:display--inline-block
:display--inline-flex
:display--inline-grid
:display--inline-table
:display--list-item
:display--table
:display--table-cell
:display--table-column-group
:display--table-footer-group
:display--table-header-group
:display--table-row
:display--table-row-group
:ff--
:font-family--
:font-size--
:font-variant--
:font-weight--
:fs--
:fv--
:fw--
:g--
:ga--
:gac--
:gaf--
:gar--
:gc--
:gce--
:gcs--
:gr--
:gre--
:grid--
:grid-area--
:grid-auto-columns--
:grid-auto-flow--
:grid-auto-rows--
:grid-column--
:grid-column-end--
:grid-column-start--
:grid-row--
:grid-row-end--
:grid-row-start--
:grid-template--
:grid-template-areas--
:grid-template-columns--
:grid-template-rows--
:grs--
:gt--
:gta--
:gtc--
:gtr--
:h--
:height--
:jc--c
:jc--e
:jc--fe
:jc--fs
:jc--l
:jc--n
:jc--r
:jc--s
:jc--sa
:jc--sb
:jc--se
:ji--a
:ji--c
:ji--e
:ji--fe
:ji--fs
:ji--l
:ji--n
:ji--r
:ji--s
:ji--se
:ji--ss
:justify-content--center
:justify-content--end
:justify-content--flex-end
:justify-content--flex-start
:justify-content--left
:justify-content--normal
:justify-content--right
:justify-content--space-around
:justify-content--space-between
:justify-content--space-evenly
:justify-content--start
:justify-items--auto
:justify-items--center
:justify-items--end
:justify-items--flex-end
:justify-items--flex-start
:justify-items--left
:justify-items--normal
:justify-items--right
:justify-items--self-end
:justify-items--self-start
:justify-items--start
:lh--
:line-height--
:m--
:margin--
:margin-bottom--
:margin-left--
:margin-right--
:margin-top--
:mb--
:ml--
:mr--
:mt--
:o--
:opacity--
:p--
:padding--
:padding-bottom--
:padding-left--
:padding-right--
:padding-top--
:pb--
:pl--
:pr--
:pt--
:ta--c
:ta--e
:ta--j
:ta--ja
:ta--l
:ta--mp
:ta--r
:ta--s
:td--lt
:td--o
:td--u
:tdc--
:tdl--lt
:tdl--o
:tdl--u
:tds--s
:tds--w
:tdt--ff
:text-align--center
:text-align--end
:text-align--justify
:text-align--justify-all
:text-align--left
:text-align--match-parent
:text-align--right
:text-align--start
:text-decoration--line-through
:text-decoration--overline
:text-decoration--underline
:text-decoration-color--
:text-decoration-line--line-through
:text-decoration-line--overline
:text-decoration-line--underline
:text-decoration-style--solid
:text-decoration-style--wavy
:text-decoration-thickness--from-font
:text-transform--captitalize
:text-transform--full-width
:text-transform--lowercase
:text-transform--uppercase
:tt--c
:tt--fw
:tt--l
:tt--u
:v--c
:v--h
:v--v
:va--b
:va--m
:va--s
:va--t
:va--tb
:va--tt
:vertical-align--baseline
:vertical-align--middle
:vertical-align--sub
:vertical-align--text-bottom
:vertical-align--text-top
:vertical-align--top
:visibility--collapse
:visibility--hidden
:visibility--visibile
:w--
:white-space--nowrap
:white-space--pre
:white-space--pre-line
:white-space--pre-wrap
:width--
:ws--n
:ws--p
:ws--pl
:ws--pw
:z--
:z-index--
:z-index--
:zi--

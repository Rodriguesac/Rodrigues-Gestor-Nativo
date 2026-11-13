package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class CatalogAdminTest {
    @Test fun parsesLocalCurrencyExactly() {
        assertEquals(19.9, MoneyInput.parse("19,90")!!, 0.001)
        assertEquals(0.0, MoneyInput.parse("0")!!, 0.001)
        assertNull(MoneyInput.parse("-10"))
        assertNull(MoneyInput.parse("19,999"))
        assertNull(MoneyInput.parse("1e8"))
        assertNull(MoneyInput.parse("10000,01"))
    }
    @Test fun rejectsEmptyItemsAndUnsafePhotoUrls() {
        assertNotNull(CatalogDraft(name="").validate())
        assertNotNull(CatalogDraft(name="Açaí 500ml",imageUrl="http://example.com/a.jpg").validate())
        assertNotNull(CatalogDraft(name="Açaí 500ml",imageUrl="javascript:alert(1)").validate())
        assertNull(CatalogDraft(name="Açaí 500ml",imageUrl="https://example.com/a.jpg").validate())
    }
    @Test fun validatesAllVariantsAndCategories() {
        assertNull(CatalogDraft(collection="cardapio_acai",name="Copo 500ml",
            variantPrices=mapOf("origem" to "10,00","cupuacu" to "12,50")).validate())
        assertNotNull(CatalogDraft(collection="cardapio_acai",name="Copo 500ml",
            variantPrices=mapOf("cupuacu" to "-1")).validate())
        assertNotNull(CatalogDraft(collection="inexistente",name="Copo").validate())
    }
    @Test fun newItemStartsPausedOnlyWhenExplicitlyDisabled() {
        val draft=CatalogDraft(collection="products",name="Açaí na Garrafa",available=false,active=true)
        assertNull(draft.validate())
        assertFalse(draft.available)
        assertTrue(draft.active)
    }
    @Test fun preservesDetailsWhenEditingItem() {
        val old=CatalogItem("id","products","Garrafa 500ml",18.0,false,true,
            "Descrição","https://example.com/image.webp",4,20.0)
        val draft=CatalogDraft.from(old)
        assertEquals("Garrafa 500ml",draft.name)
        assertEquals("18,00",draft.price)
        assertFalse(draft.available)
        assertEquals("20,00",draft.compareAtPrice)
    }
}

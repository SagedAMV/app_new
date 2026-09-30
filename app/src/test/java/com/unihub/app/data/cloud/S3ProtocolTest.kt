package com.unihub.app.data.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class S3ProtocolTest {
    @Test fun namesAreEncodedAsRfc3986NotFormData() {
        assertEquals("folder/a%20%2B%20%26.pdf", S3Encoding.path("folder/a + &.pdf"))
        assertFalse(S3Encoding.path("محاضرة أولى.pdf").contains('+'))
        assertTrue(S3Encoding.path("محاضرة أولى.pdf").contains("%20"))
    }
    @Test fun continuationTokensAreSortedAndEncoded() {
        assertEquals("continuation-token=a%2Fb%2Bc%3D&list-type=2", S3Encoding.query(mapOf("list-type" to "2", "continuation-token" to "a/b+c=")))
    }
    @Test fun reservedCharactersArePreservedCorrectly() {
        assertEquals("~%2A%25", S3Encoding.component("~*%"))
    }
    @Test fun xmlEntitiesDoNotCorruptObjectNames() {
        val xml = """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated><Contents><Key> a &amp; b.pdf </Key><Size>0</Size><ETag>&quot;v1&quot;</ETag></Contents></ListBucketResult>"""
        val page = R2ListParser.parse(xml)
        assertEquals(" a & b.pdf ", page.objects.single().key)
        assertEquals(0L, page.objects.single().size)
        assertEquals("v1", page.objects.single().etag)
        assertNull(page.nextToken)
    }
    @Test fun encodedXmlNamesAndArabicAreDecoded() {
        val key = "ملف + جديد.pdf"
        val xml = """<ListBucketResult><EncodingType>url</EncodingType><IsTruncated>false</IsTruncated><Contents><Key>${S3Encoding.component(key)}</Key><Size>15</Size><ETag>v</ETag></Contents></ListBucketResult>"""
        assertEquals(key, R2ListParser.parse(xml).objects.single().key)
    }
    @Test fun pagesKeepContinuationToken() {
        val page = R2ListParser.parse("<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>a/b+c=</NextContinuationToken></ListBucketResult>")
        assertEquals("a/b+c=", page.nextToken)
    }
    @Test fun prefixedXmlNamespacesAreSupported() {
        val xml = """<s:ListBucketResult xmlns:s="urn:s3"><s:IsTruncated>false</s:IsTruncated><s:Contents><s:Key>a</s:Key><s:Size>4</s:Size><s:ETag>v</s:ETag></s:Contents></s:ListBucketResult>"""
        assertEquals("a", R2ListParser.parse(xml).objects.single().key)
    }
    @Test(expected = IOException::class) fun truncatedListWithoutTokenIsRejected() {
        R2ListParser.parse("<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>")
    }
    @Test(expected = IOException::class) fun externalEntitiesAreRejected() {
        R2ListParser.parse("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>")
    }
    @Test fun thousandObjectsAreParsedAndNextPageRequested() {
        val objects = (1..1000).joinToString("") { "<Contents><Key>file-$it.pdf</Key><Size>$it</Size><ETag>v$it</ETag></Contents>" }
        val page = R2ListParser.parse("<ListBucketResult><IsTruncated>true</IsTruncated>$objects<NextContinuationToken>next</NextContinuationToken></ListBucketResult>")
        assertEquals(1000, page.objects.size)
        assertEquals("next", page.nextToken)
    }
}

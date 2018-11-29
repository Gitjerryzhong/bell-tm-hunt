package cn.edu.bnuz.bell.hunt.utils

import cn.edu.bnuz.bell.hunt.Review

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipTools {

    static byte[] zip(Review review, String baseDir) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ZipOutputStream zipFile = new ZipOutputStream(baos)

        if (review.mainInfoForm) {
            addEntry("${baseDir}/${review.mainInfoForm}", "${outputFileName('main', review, getExt(review.mainInfoForm))}", zipFile)
        }
        if (review.proofFile) {
            addEntry("${baseDir}/${review.proofFile}", "${outputFileName('proof', review, getExt(review.proofFile))}", zipFile)
        }
        if (review.summaryReport) {
            addEntry("${baseDir}/${review.summaryReport}", "${outputFileName('summary', review, getExt(review.summaryReport))}", zipFile)
        }

        zipFile.finish()

        return baos.toByteArray()
    }

    private static addEntry(String baseName, String outputName, ZipOutputStream zipFile) {
        File file = new File(baseName)
        if (file?.exists() && file.isFile() && file.name.indexOf("bak_") == -1) {
            zipFile.putNextEntry(new ZipEntry(outputName))
            file.withInputStream { input -> zipFile << input }
            zipFile.closeEntry()
        }
    }

    private static preLabel(String pre, Integer reportType) {
        def labelMap = [
                main: ['申报书', '验收登记表'],
                proof: '主要佐证材料',
                summary: '总结报告',
                other: '其他']
        if (pre == 'main') {
            return labelMap.main[reportType ==1 ? 0 : 1]
        } else {
            return labelMap[pre]
        }
    }

    private static outputFileName(String pre, Review review, String ext) {
        if (review.reportType == 1) {
            return nameConvention(pre, review.project.name, review.project.level.name(), review.project.subtype.name, review.project.principal.name, ext)
        } else {
            return nameConvention(pre, review.reportType, review.project.code, review.project.level.name(), review.project.subtype.name, review.project.principal.name, ext)
        }
    }

    private static levelLabel(String level) {
        def labelMap = [
                UNIVERSITY: '校级',
                CITY: '市级',
                PROVINCE: '省级',
                NATION: '国家'
        ]
        return labelMap[level]
    }

    private static getExt(String fileName) {
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
    }

    static nameConvention(String pre, String prejectName, String levelName, String subTypeName, String teacherName, String ext) {
        return "${preLabel(pre, 1)}-${prejectName}-${levelLabel(levelName)}-${subTypeName}-${teacherName}.${ext}"
    }

    static nameConvention(String pre, Integer reportType, String projectCode, String levelName, String subTypeName, String teacherName, String ext) {
        return "${preLabel(pre, reportType)}-${levelLabel(levelName)}-${projectCode}-${subTypeName}-${teacherName}.${ext}"
    }
}

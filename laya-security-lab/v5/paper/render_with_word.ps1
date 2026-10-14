param([string]$DocPath, [string]$PdfPath)
$ErrorActionPreference = 'Stop'
$paperWord = $null
$paperDocument = $null
$paperOwned = $false
try {
    $paperWord = New-Object -ComObject Word.Application
    if ($paperWord.Documents.Count -ne 0) { throw 'Refusing to alter a Word session containing other documents.' }
    $paperOwned = $true
    $paperWord.Visible = $false
    $paperWord.DisplayAlerts = 0
    $paperDocument = $paperWord.Documents.Open($DocPath, $false, $true, $false)
    $paperDocument.Repaginate()
    $paperDocument.ExportAsFixedFormat($PdfPath, 17)
    Write-Output ('WORD_RENDER_PAGES ' + $paperDocument.ComputeStatistics(2))
} finally {
    if ($null -ne $paperDocument) {
        $paperDocument.Close(0)
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($paperDocument)
    }
    if ($null -ne $paperWord) {
        if ($paperOwned -and $paperWord.Documents.Count -eq 0) {
            $paperSaveOption = 0
            $paperWord.Quit([ref]$paperSaveOption)
        }
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($paperWord)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
